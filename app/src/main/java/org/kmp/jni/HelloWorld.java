package org.kmp.jni;

public class HelloWorld {
    public native void print();

    static {
        System.loadLibrary("HelloWorld"); // Name of your DLL without "lib" or ".dll"
    }

    public static void main(String[] args) {
        new HelloWorld().print();
    }
}
