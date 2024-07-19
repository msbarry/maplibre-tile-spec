package com.mlt.converter.encodings.fsst;

import java.io.IOException;
import java.util.Locale;

public class FsstEncoder {
  public static final Fsst INSTANCE;

  static {
    String fsst = "jni";
    try {
      String env = System.getenv("FSST");
      if (env != null) fsst = env.toLowerCase(Locale.ROOT);
    } catch (SecurityException e) {
      // use default value
    }
    INSTANCE =
        switch (fsst) {
          case "java" -> new FsstJava();
          case "debug" -> new FsstDebug();
          default -> new FsstJni();
        };
  }

  private FsstEncoder() {}

  public static SymbolTable encode(byte[] data) {
    return INSTANCE.encode(data);
  }

  public static byte[] decode(byte[] symbols, int[] symbolLengths, byte[] compressedData)
      throws IOException {
    return INSTANCE.decode(symbols, symbolLengths, compressedData);
  }
}
