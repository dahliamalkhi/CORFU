; Query endpoint status given the endpoint as the first arg
(in-ns 'org.corfudb.shell) ; so our IDE knows what NS we are using

(import org.docopt.Docopt) ; parse some cmdline opts
(require 'clojure.pprint)
(require 'clojure.java.shell)
(def usage "corfu_query.
Usage:
  corfu_query <endpoint> [-e [-u <keystore> -f <keystore_password_file>] [-r <truststore> -w <truststore_password_file>] [-g -o <username_file> -j <password_file>]]
Options:
  -e, --enable-tls                                                                       Enable TLS.
  -u <keystore>, --keystore=<keystore>                                                   Path to the key store.
  -f <keystore_password_file>, --keystore-password-file=<keystore_password_file>         Path to the file containing the key store password.
  -r <truststore>, --truststore=<truststore>                                             Path to the trust store.
  -w <truststore_password_file>, --truststore-password-file=<truststore_password_file>   Path to the file containing the trust store password.
  -g, --enable-sasl-plain-text-auth                                                      Enable SASL Plain Text Authentication.
  -o <username_file>, --sasl-plain-text-username-file=<username_file>                    Path to the file containing the username for SASL Plain Text Authentication.
  -j <password_file>, --sasl-plain-text-password-file=<password_file>                    Path to the file containing the password for SASL Plain Text Authentication.
  -h, --help     Show this screen.
")

(def localcmd (.. (new Docopt usage) (parse *args)))

(defn print-query [endpoint] (do
                               (println (format "Query %s:" endpoint))
                               (let [q (.. (get-base-client (get-router server localcmd) 0 (.UUID (.fromString "00000000-0000-0000-0000-000000000000"))) (getVersionInfo))]
                               (println (bean (.. q (get)))))
                               ))

(print-query (.. localcmd (get "<endpoint>")))
