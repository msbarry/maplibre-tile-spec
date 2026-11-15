package org.maplibre.mlt.converter.encodings.fsst;

class FsstJava implements Fsst {

  @Override
  public SymbolTable encode(byte[] data) {
    return new SymbolTableBuilder().encode(data);
  }
}
