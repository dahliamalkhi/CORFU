/**
 * Autogenerated by Thrift Compiler (0.9.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
package com.microsoft.corfu;


import java.util.Map;
import java.util.HashMap;
import org.apache.thrift.TEnum;

public enum ExtntMarkType implements org.apache.thrift.TEnum {
  EX_BEGIN(0),
  EX_MIDDLE(1),
  EX_SKIP(2);

  private final int value;

  private ExtntMarkType(int value) {
    this.value = value;
  }

  /**
   * Get the integer value of this enum value, as defined in the Thrift IDL.
   */
  public int getValue() {
    return value;
  }

  /**
   * Find a the enum type by its integer value, as defined in the Thrift IDL.
   * @return null if the value is not found.
   */
  public static ExtntMarkType findByValue(int value) { 
    switch (value) {
      case 0:
        return EX_BEGIN;
      case 1:
        return EX_MIDDLE;
      case 2:
        return EX_SKIP;
      default:
        return null;
    }
  }
}
