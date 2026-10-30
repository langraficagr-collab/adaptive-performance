package com.mauricio.adaptiveperformance;
interface IPrivilegedService {
    String exec(String command) throws Exception;
    long[] costStats() throws Exception;
}
