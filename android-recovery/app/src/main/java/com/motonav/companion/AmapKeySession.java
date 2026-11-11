package com.motonav.companion;
import java.util.Arrays;
/** App-owned in-memory session only; SDK/String copies cannot be securely erased. */
public final class AmapKeySession {
 private char[] value=new char[0];
 public boolean replace(String candidate){ clear(); if(!AmapAccess.configured(candidate))return false;value=candidate.toCharArray();return true; }
 public boolean configured(){return value.length==32;}
 public String sdkValue(){return new String(value);}
 public void clear(){Arrays.fill(value,'\0');value=new char[0];}
}
