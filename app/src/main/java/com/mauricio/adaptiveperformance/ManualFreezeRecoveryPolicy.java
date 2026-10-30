package com.mauricio.adaptiveperformance;
public final class ManualFreezeRecoveryPolicy {
 private ManualFreezeRecoveryPolicy(){}
 @FunctionalInterface public interface Command { boolean run() throws Exception; }
 @FunctionalInterface public interface Reader { String read() throws Exception; }
 public static boolean restore(String old, Command c, Reader r) {
  if(old==null||old.isEmpty())return false;
  try {return old.equals(r.read()) || (c.run() && old.equals(r.read()));}
  catch(Exception e){return false;}
 }
 public static boolean commandOnly(Command c) {
  try{return c.run();}catch(Exception e){return false;}
 }
}
