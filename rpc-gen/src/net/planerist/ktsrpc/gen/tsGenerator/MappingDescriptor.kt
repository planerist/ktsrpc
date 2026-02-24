package net.planerist.ktsrpc.gen.tsGenerator

class MappingDescriptor(val typeName: String, val jsonToTs: (String) -> String, val tsToJson: (String) -> String)
