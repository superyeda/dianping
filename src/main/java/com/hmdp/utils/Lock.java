package com.hmdp.utils;

public interface Lock {
    boolean tryLock(Long ttl);
    void unLock();
}
