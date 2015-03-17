/**
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.corfudb.runtime;

import java.util.*;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.corfudb.runtime.collections.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


class BTreeTester<K extends Comparable<K>, V, L extends CDBAbstractBTree<K, V>> implements Runnable {

    public static enum testcase {
        functional,     // single list, basic functionality, single-threaded
        multifunctional,// multiple lists, basic functionality, single-threaded
        concurrent,     // single list, random ops, concurrent
        tx              // multiple lists, random ops, concurrent
    }

    public static enum optype {
        get,
        put,
        rem,
        move
    }

    public class Operation {
        public optype op;
        public L src;
        public L dst;
        public K key;
        public V val;
        public Operation(optype o, L s, L d, K k, V v) {
            op = o;
            src = s;
            dst = d;
            key = k;
            val = v;
        }
    }

    protected Operation newGet(L s, K k, V v) { return new Operation(optype.get, s, null, k, v); }
    protected Operation newPut(L d, K k, V v) { return new Operation(optype.put, null, d, k, v); }
    protected Operation newRemove(L s, K k, V v) { return new Operation(optype.rem, s, null, k, v); }
    protected Operation newMove(L s, L d, K k, V v) { return new Operation(optype.move, s, d, k, v); }

    private static Logger dbglog = LoggerFactory.getLogger(TXListTester.class);

    // TODO: eventually this should become obsolete and should be removed!
    private static boolean writeOnlyTxSupport = true;
    public static boolean extremeDebug = false;
    public static boolean trackOps = true;

    AbstractRuntime m_rt;           // corfu runtime
    List<L> m_v;                    // list of b-trees we are reading/updating
    CyclicBarrier m_startbarrier;   // start barrier to ensure all threads finish init before tx loop
    CyclicBarrier m_stopbarrier;    // stop barrier to ensure no thread returns until all have finished
    int m_nOps;                     // number of operations over the field of lists
    int m_nKeys;                    // number of items to add to each list
    int m_nReservedKeyFraction;     // divisor fraction of the initial key space to reserve to serve random puts
    AtomicInteger m_keycounter;     // counter to use for key requests
    int m_nId;                      // worker id
    long m_startwork;               // system time in milliseconds when tx loop starts
    long m_endwork;                 // system time in milliseconds when tx loop completes
    double m_readWriteRatio;        // ratio of reads to writes
    ElemGenerator<K> m_keygen;      // key generator to populate random trees.
    ElemGenerator<V> m_valgen;      // element generator to populate random trees.
    boolean m_verbose;              // emit copious dbg text to console?
    int m_nattempts;                // number of attempts (a tx may need to be retried many times)
    int m_numcommits;               // number of committed transactions
    int m_naborts;                  // number of aborts
    int m_ntotalretries;            // retries due to inconsistent views (opacity violations)
    testcase m_testcase;            // which test scenario?
    Map<optype, ArrayList<Operation>> m_funcops;  // functional test known scenario of ops
    ReentrantLock m_gtlock;         // ground truth lock
    TreeMap<K, V> m_entries;        // for functional tests--maintain the mapping from key to value
    TreeMap<K, L> m_membership;     // for functional tests--which b tree do we think holds the given key?
    TreeMap<L, HashSet<K>> m_rm;    // for functional tests--which lists contains which keys?
    TreeMap<K, V> m_orphans;        // random put attempts may fail because of tx abort, which is a legitimate
                                    // case we must account for

    /**
     * getEndToEndLatency
     * Execution time for the benchmark is the time delta between when the
     * first tester thread enters its transaction phase and when the last
     * thread exits the same. The run method tracks these per object.
     * @param testers
     * @return
     */
    static long
    getEndToEndLatency(BTreeTester[] testers) {
        long startmin = Long.MAX_VALUE;
        long endmax = Long.MIN_VALUE;
        for(BTreeTester tester : testers) {
            startmin = Math.min(startmin, tester.m_startwork);
            endmax = Math.max(endmax, tester.m_endwork);
        }
        return endmax - startmin;
    }

    /**
     * return the total number of committed
     * operations for the worker thread group.
     * @param testers
     * @return
     */
    static int
    getCommittedOps(BTreeTester[] testers) {
        int committed = 0;
        for(BTreeTester tester : testers)
            committed += tester.m_numcommits;
        return committed;
    }

    /**
     * console logging for verbose mode.
     * @param strFormat
     * @param args
     */
    protected void
    inform(
            String strFormat,
            Object... args
        )
    {
        if(m_verbose)
            System.out.format(strFormat, args);
    }

    /**
     * create a list of operations to test basic functionality
     * @return
     */
    Map<optype, ArrayList<Operation>> createOpList() {
        ArrayList<Operation> pops = new ArrayList<Operation>();
        ArrayList<Operation> gops = new ArrayList<Operation>();
        ArrayList<Operation> rops = new ArrayList<Operation>();
        L singleTree = m_v.get(0);
        for(int i=0; i<m_nKeys; i++) {
            String skey = "key_" + i;
            String sval = "val_" + i;
            Comparable<K> ckey = (Comparable) skey;
            pops.add(newPut(singleTree, (K)ckey, (V)sval));
        }
        for(int i=0; i<m_nKeys; i++) {
            String skey = "key_" + i;
            Comparable<K> ckey = (Comparable) skey;
            gops.add(newGet(singleTree, (K)ckey, (V) null));
        }
        for(int i=0; i<m_nKeys; i++) {
            String skey = "key_" + i;
            Comparable<K> ckey = (Comparable) skey;
            rops.add(newRemove(singleTree, (K) ckey, (V) null));
        }
        Map<optype, ArrayList<Operation>> ops = new TreeMap<optype, ArrayList<Operation>>();
        ops.put(optype.put, pops);
        ops.put(optype.get, gops);
        ops.put(optype.rem, rops);
        return ops;
    }

    /**
     *
     * ctor
     * @param nId           worker id
     * @param startbarrier  barrier: all threads complete init phase before starting work
     * @param stopbarrier   barrier: all threads complete all work before benchmark timing stops
     * @param tcr           corfu runtime
     * @param v             field of trees over which to apply random reads/updates
     * @param nops          number of read or update ops to apply
     * @param nkeys         number of items per tree
     * @param rwpct         ratio of reads to updates
     * @param keygen        random key generator for tree population
     * @param valgen        random element generator for tree population
     */
    public
    BTreeTester(
            int nId,
            CyclicBarrier startbarrier,
            CyclicBarrier stopbarrier,
            AbstractRuntime tcr,
            List<L> v,
            testcase tcase,
            int nops,
            int nkeys,
            double rwpct,
            ElemGenerator<K> keygen,
            ElemGenerator<V> valgen,
            boolean _verbose
            )
    {
        m_nId = nId;
        m_nOps = nops;
        m_nKeys = nkeys;
        m_v = v;
        m_rt = tcr;
        m_startbarrier = startbarrier;
        m_stopbarrier = stopbarrier;
        m_keygen = keygen;
        m_valgen = valgen;
        m_verbose = _verbose;
        m_numcommits =  0;
        m_naborts = 0;
        m_ntotalretries = 0;
        m_nattempts = 0;
        m_readWriteRatio = rwpct;
        m_testcase = tcase;
        m_entries = new TreeMap<K, V>();
        m_membership = new TreeMap<K, L>();
        m_rm = new TreeMap<L, HashSet<K>>();
        m_nReservedKeyFraction = 2;
        m_keycounter = new AtomicInteger(0);
        m_gtlock = new ReentrantLock();
    }

    /**
     * selectRandomTrees
     * @return a pair of trees to be used as
     * source and destination in a random move
     * or random get.
     */
    private List<L>
    selectRandomTrees(
        int nTrees
        )
    {
        ArrayList<L> result = new ArrayList<L>();
        if(nTrees > m_v.size())
            return result;
        if(nTrees == m_v.size()) {
            result.addAll(m_v);
            return result;
        }

        ArrayList<L> trees = new ArrayList<L>();
        trees.addAll(m_v);

        while(trees.size() > 0 && result.size() < nTrees) {
            int lidx = trees.size() == 1 ? 0 : (int) (Math.random() * trees.size());
            assert(lidx >= 0 && lidx < trees.size());
            L randtree = trees.remove(lidx);
            result.add(trees.remove(lidx));
        }

        return result;
    }

    /**
     * getRandomKey
     * given a tree, choose a random element if possible. Sadly, this
     * is not trivial, unless the actual underlying tree supports
     * range queries with floor/ceiling support. For now, use the ground
     * truth maps.
     * @param src
     */
    private K
    getRandomKey(L src) {

        K randkey = null;
        m_gtlock.lock();
        try {
            int i = 0;
            HashSet<K> keys = m_rm.get(src);
            int range = keys.size();
            int lidx = (int) Math.floor(Math.random() * range);
            for (K k : keys) {
                if (lidx == i) {
                    randkey = k;
                    break;
                }
            }
            if (randkey == null)
                randkey = keys.iterator().next();
        } finally {
            m_gtlock.unlock();
        }
        return randkey;
    }

    /**
     * given an input list, read
     * a random item from that list
     */
    private Operation
    randomGet() {
        L src = selectRandomTrees(1).get(0);
        K key = getRandomKey(src);
        V val = src.get(key);
        inform("T[%d]   get L%d(%s,%s)\n", m_nId, src.oid, key.toString(), val.toString());
        return newGet(src, key, val);
    }

    /**
     * perform a random put
     */
    private Operation
    randomPut() {
        L src = selectRandomTrees(1).get(0);
        K key = m_keygen.randElem(m_keycounter.getAndIncrement());
        V val = m_valgen.randElem(Math.random());
        src.put(key, val);
        inform("T[%d]   put L%d(%s,%s)\n", m_nId, src.oid, key.toString(), val.toString());
        return newPut(src, key, val);
    }

    /**
     * randomly remove a key
     * @return
     */
    private Operation
    randomRemove() {
        L src = selectRandomTrees(1).get(0);
        K key = getRandomKey(src);
        V val = src.remove(key);
        inform("T[%d]   del L%d(%s,%s)\n", m_nId, src.oid, key.toString(), val.toString());
        return newRemove(src, key, val);
    }

    /**
     * moveRandomItem
     * given a pair of trees, randomly choose an element from the
     * source and move it to the destination tree.
     */
    private Operation
    randomMove() {

        List<L> lists = selectRandomTrees(2);
        L src = lists.get(0);
        L dst = lists.get(1);
        K key = getRandomKey(src);
        V val = src.get(key);
        src.remove(key);
        dst.put(key, val);
        inform("[T%d]   mov L%d[%s,%s]->L%d\n", m_nId, src.oid, key.toString(), val.toString(), dst.oid);
        return newMove(src, dst, key, val);
    }

    /**
     * performRandomOp
     * According the distribution indicated by m_readWriteRatio,
     * perform a read-only operation (get an item from a list)
     * or a read-write tx (move a random item between lists)
     */
    private Operation
    performRandomOp() {
        Operation op;
        double diceRoll = Math.random();
        if(diceRoll < m_readWriteRatio) {
            op = randomGet();
        } else {
            diceRoll = Math.random();
            op = (diceRoll < 0.33) ?
                    randomPut() : (diceRoll < 0.66) ?
                    randomRemove() : randomMove();
        }
        return op;
    }

    /**
     * perform the next operation
     * @param i
     * @return
     */
    private Operation
    performNextOperation(int i) {
        if(m_testcase == testcase.functional || m_testcase == testcase.multifunctional) {
            ArrayList<Operation> curops;
            if(i >= 2 * m_nKeys)
                curops = m_funcops.get(optype.rem);
            else if(i >= m_nKeys)
                curops = m_funcops.get(optype.get);
            else
                curops = m_funcops.get(optype.put);
            Operation op = curops.get(i % 3);
            L l = op.src == null ? op.dst : op.src;
            K k = op.key;
            V v = op.val;
            switch(op.op) {
                case put: l.put(k, v); break;
                case get: op.val = l.get(k); break;
                case rem: op.val = l.remove(k); break;
            }
            return op;
        }
        return performRandomOp();
    }

    /**
     * return the number of operations in the test
     * @return
     */
    private int
    numOperations() {
        return (m_testcase == testcase.functional || m_testcase == testcase.multifunctional) ?
                (m_nKeys * 3) : m_nOps;
    }


    /**
     * populate
     * randomly distribute some fraction of the key space over
     * the available trees in the field. Preserve the remaining
     * keys to serve random puts.
     */
    public void
    populate() {

        if(m_nId != 0)
            return;

        if(m_testcase == testcase.functional || m_testcase == testcase.multifunctional) {

            // create a single list of known operations
            // and play them over a single tree
            m_funcops = createOpList();

        } else {

            // randomly populate the field of trees
            for (int i = 0; i < m_nKeys / m_nReservedKeyFraction; i++) {
                int lidx = (int) (Math.random() * m_v.size());
                L randtree = m_v.get(lidx);
                m_rt.BeginTX();
                if (!writeOnlyTxSupport)
                    randtree.size();
                K key = m_keygen.randElem(m_keycounter.getAndIncrement());
                V val = m_valgen.randElem(Math.random());
                randtree.put(key, val);
                inform("T[%d]   init-put L%d(%s,%s)\n", m_nId, randtree.oid, key.toString(), val.toString());
                boolean success = m_rt.EndTX();
                trackOperation(newPut(randtree, key, val), success);
            }

        }
    }

    /**
     * update the ground truth to reflect the success or failure
     * of the last attempted mutation of the tree field.
     * @param op
     * @param success
     */
    void trackOperation(Operation op, boolean success) {

        if(!trackOps)
            return;

        m_gtlock.lock();
        try {
            K key = op.key;
            V val = op.val;
            L src = op.src;
            L dst = op.dst;
            switch(op.op) {
                case get:
                    break;
                case move:
                    if(success) {
                        assert(m_rm.containsKey(src));
                        assert(m_rm.containsKey(dst));
                        HashSet<K> srcKeys = m_rm.get(src);
                        HashSet<K> dstKeys = m_rm.get(dst);
                        assert(srcKeys.contains(key));
                        assert(!dstKeys.contains(key));
                        srcKeys.remove(key);
                        dstKeys.add(key);
                    }
                    break;
                case put:
                    m_entries.put(key, val);
                    if(success) {
                        if (!m_rm.containsKey(dst))
                            m_rm.put(dst, new HashSet<K>());
                        HashSet<K> dstKeys = m_rm.get(dst);
                        dstKeys.add(key);
                        m_membership.put(key, dst);
                    } else {
                        m_orphans.put(key, val);
                    }
                    break;
                case rem:
                    if(success) {
                        assert(m_rm.containsKey(src));
                        assert(!m_orphans.containsKey(key));
                        assert(m_membership.containsKey(key));
                        HashSet<K> srcKeys = m_rm.get(src);
                        srcKeys.remove(key);
                        m_membership.remove(key);
                        m_orphans.put(key, val);
                    }
                    break;
            }

        } finally {
            m_gtlock.unlock();
        }
    }


    /**
     * use the start barrier to wait for
     * all worker threads to initialize
     */
    private void awaitInit() {
        try {
            m_startbarrier.await();
        } catch(Exception bbe) {
            throw new RuntimeException(bbe);
        }
        inform("Entering run loop for tx list tester thread %d\n", m_nId);
        m_startwork = System.currentTimeMillis();
    }

    /**
     * use the start barrier to wait for
     * all worker threads to complete the tx loop
     */
    private void awaitComplete() {
        m_endwork = System.currentTimeMillis();
        try {
            m_stopbarrier.await();
        } catch(Exception bbe) {
            throw new RuntimeException(bbe);
        }
        inform("Leaving run loop for tx list tester thread %d\n", m_nId);
    }

    private static Object slock = new Object();

    /**
     * dump the state of the tree(s) used in the last transaction attempt
     * as close to atomically as possible.
     * Which isn't really all that close. Option to use a transaction
     * or not--this is a debug utility, and we may want to see uncommitted
     * state as often as not.
     * @param op
     * @param txid
     * @param attemptid
     * @param committed
     * @param usetx
     */
    private void
    dumpTrees(
            Operation op,
            int txid,
            long attemptid,
            boolean committed,
            boolean usetx
        )
    {
        if(!extremeDebug) return;
        String strSrc = null;
        String strDst = null;
        String strOp = committed ? "post-cmt" : "post-abt";
        boolean done = false;

        while(!done) {
            boolean inTX = false;
            try {
                if(usetx) m_rt.BeginTX();
                inTX = usetx;
                strSrc = op.src == null ? "" : op.src.print();
                strDst = op.dst == null ? "" : op.dst.print();
                done = usetx ? m_rt.EndTX() : true;
                inTX = false;
            } catch (Exception e) {
                if(inTX) m_rt.AbortTX();
                inTX = false;
            }
        }

        synchronized (slock) {
            L first = op.src == null ? op.dst : op.src;
            L second = op.src == null ? op.src : op.dst;
            inform("[T%d]   %s[#%d, try:%d]->src:L%d=%s\n",
                    m_nId, strOp, txid, attemptid, first.oid, strSrc);
            if(second != null) {
                inform("       %s[#%d, try:%d]->dst:L%d=%s\n", strOp, txid, attemptid, second.oid, strDst);
            }
        }
    }

    /**
     * run method (runnable)
     * wait for all worker threads to initialize
     * perform the specified number of random transactions
     * wait for all worker threads to complete the tx loop.
     */
    public void run()
    {
        populate();
        awaitInit();
        for(int i=0;i<numOperations();i++)
        {
            long attempts = 0;
            long icretries = 0;
            boolean done = false;
            while(!done) {
                inform("[T%d] begintx[#%d, try:%d]\n", m_nId, i, attempts);
                boolean inTX = false;
                try {
                    attempts++;
                    m_nattempts++;
                    m_rt.BeginTX();
                    inTX = true;
                    Operation res = performNextOperation(i);
                    done = m_rt.EndTX();
                    inTX = false;
                    inform("[T%d] endtx[#%d, try:%d]->%s\n", m_nId, i, attempts-1, (done?"COMMIT":"ABORT"));
                    m_numcommits += done ? 1 : 0;
                    m_naborts += done ? 0 : 1;
                    trackOperation(res, done);
                    dumpTrees(res, i, attempts, done, true);
                } catch (Exception e) {
                    inform("[T%d] force retry tx[%d, try%d] because of exception "+e+"\n", m_nId, i, attempts-1);
                    icretries++;
                    if(inTX) m_rt.AbortTX();
                }
            }
            m_ntotalretries += icretries;
        }
        awaitComplete();
        System.out.format("[T%d] done(%d ops): %d commits of %d attempts with %d retries for inconsistent views...\n",
                m_nId, m_nOps, m_numcommits, m_nattempts, m_ntotalretries);
    }

    /**
     * "random" integer generator--just returns the index supplied for now.
     */
    static class SeqIntGenerator implements ElemGenerator<Integer> {
        public Integer randElem(Object i) {
            return new Integer((Integer) i);
        }
    }

    /**
     * "random" key generator--just returns key plus the index supplied for now.
     */
    static class SeqKeyGenerator implements ElemGenerator<String> {
        public String randElem(Object i) {
            return new String("key_"+(Integer)i);
        }
    }

    /**
     * "random" val generator--just returns val plus the index supplied for now.
     */
    static class SeqValGenerator implements ElemGenerator<String> {
        public String randElem(Object i) {
            return new String("val_"+(Integer)i);
        }
    }


    /**
     * check whether the end state of the field
     * of lists is consistent with a serial order
     * of all the operations. Because the key space
     * is unique, (see the entirely not random random
     * generator above), each element must be present
     * in exactly one of the lists.
     * @param rt    runtime
     * @param v     list of lists
     * @param <K>   key type
     * @param <V>   val type
     * @param <L>   tree type
     * @return true if the state of the
     *      list is consistent
     */
    static <K extends Comparable<K>, V, L extends CDBAbstractBTree<K, V>> boolean
    isConsistent(
            AbstractRuntime rt,
            List<L> v,
            int expectedKeys,
            Map<K, V> entries,
            StringBuilder strDetails
            )
    {
        int violations = 0;
        int totalElems = 0;
        boolean consistent = true;
        boolean failfast = strDetails == null;
        List<String> failures = failfast ? null : new LinkedList<String>();
        for(L l : v) {
            rt.BeginTX();
            totalElems += l.size();
            rt.EndTX();
        }
//        for(L l : v) {
//            rt.BeginTX();
//            int siz = l.size();
//            for (int i=0; i<siz; i++) {
//                E e = l.get(i);
//                for (L lB : v) {
//                    if (lB != l && lB.contains(e)) {
//                        consistent = false;
//                        violations++;
//                        if(failfast) break;
//                        String failure = "" + e + " contained in L" + l.oid +
//                                " and L" + lB.oid + "\n";
//                        failures.add(failure);
//                    }
//                }
//            }
//            if(!rt.EndTX())
//                throw new RuntimeException("Consistency check aborted...");
//            if (!consistent && failfast)
//                break;
//        }

        if(totalElems != expectedKeys) {
            consistent = false;
            violations++;
            if(failures != null)
                failures.add(new String("expected "+expectedKeys+", found "+totalElems+"\n"));
            List<String> missingKeys = new LinkedList<String>();
            for(K k : entries.keySet()) {
                boolean foundi = false;
                for(L l: v) {
                    foundi = l.get(k) != null;
                    if(foundi)
                        break;
                }
                if(!foundi)
                    missingKeys.add(k.toString());
            }
            failures.add("missing keys: [" + String.join(", ", missingKeys) + "]\n");
        }

        if(!consistent && strDetails != null) {
            strDetails.append("found " + violations + " violations:\n");
            for(String s : failures)
                strDetails.append(s);
        }
        return consistent;
    }

    /**
     * create a new CDB tree object
     * @param strClass
     * @param TR
     * @param sf
     * @param oid
     * @param <K>
     * @param <V>
     * @param <L>
     * @return new list (empty) of the appropriate class
     */
    static <K extends Comparable<K>, V, L extends CDBAbstractBTree<K, V>> L
    createTree(
            String strClass,
            AbstractRuntime TR,
            StreamFactory sf,
            long oid
        )
    {
        if(strClass.contains("CDBPhysicalBTree"))
            return (L) new CDBPhysicalBTree<K, V>(TR, sf, oid);
        else if(strClass.contains("CDBLogicalBTree"))
            return (L) new CDBLogicalBTree<K,V>(TR, sf, oid);
        return null;
    }

    /**
     * run a tx list test.
     * @param TR
     * @param sf
     * @param numthreads
     * @param numlists
     * @param nOperations
     * @param numkeys
     * @param rwpct
     * @param strClass
     * @param verbose
     * @param <K>
     * @param <V>
     * @param <L>
     * @throws InterruptedException
     */
    public static <K extends Comparable<K>, V, L extends CDBAbstractBTree<K, V>> void
    runTest(
            AbstractRuntime TR,
            StreamFactory sf,
            testcase tcase,
            int numthreads,
            int numlists,
            int nOperations,
            int numkeys,
            double rwpct,
            String strClass,
            boolean verbose
        ) throws InterruptedException
    {
        ElemGenerator<K> keygen = (ElemGenerator<K>) (Object) new SeqKeyGenerator();
        ElemGenerator<V> valgen = (ElemGenerator<V>) new SeqValGenerator();
        ArrayList<L> trees = new ArrayList<L>();
        CyclicBarrier startbarrier = new CyclicBarrier(numthreads);
        CyclicBarrier stopbarrier = new CyclicBarrier(numthreads);

        for(int i=0; i<numlists; i++) {
            long oidlist = DirectoryService.getUniqueID(sf);
            L tree = BTreeTester.<K, V,L>createTree(strClass, TR, sf, oidlist);
            trees.add(tree);
        }

        Thread[] threads = new Thread[numthreads];
        BTreeTester<K, V,L>[] testers = new BTreeTester[numthreads];
        int perWorkerOps = nOperations / numthreads;
        for (int i = 0; i < numthreads; i++) {
            BTreeTester<K, V, L> txl = new BTreeTester<K, V, L>(
                    i, startbarrier, stopbarrier, TR, trees, tcase, perWorkerOps, numkeys, rwpct, keygen, valgen, verbose);
            testers[i] = txl;
            threads[i] = new Thread(txl);
            threads[i].start();
        }
        for(int i=0;i<numthreads;i++)
            threads[i].join();

        StringBuilder strDetails = new StringBuilder();
        boolean success = isConsistent(TR, trees, numkeys, testers[0].m_entries, strDetails);
        long e2e = getEndToEndLatency(testers);
        int committedops = getCommittedOps(testers);
        double throughput = (1000.0 * (double)committedops) / (double)e2e;
        System.out.format("BTree consistency check %s!\n", success ? "PASSED" : "FAILED");
        System.out.format("Throughput: %d tx in %d msec -> %f tx/sec\n", committedops, e2e, throughput);
        System.out.format("tput: %s, %d, %d, %d, %.1f, %.3f\n", strClass, numthreads, numkeys, nOperations, rwpct, throughput);
        System.out.print(strDetails.toString());
        System.out.println(TR);
    }

}

