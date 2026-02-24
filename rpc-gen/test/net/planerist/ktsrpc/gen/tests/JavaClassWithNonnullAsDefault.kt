package net.planerist.ktsrpc.gen.tests

import javax.annotation.ParametersAreNonnullByDefault

@ParametersAreNonnullByDefault
class JavaClassWithNonnullAsDefault internal constructor(
    var name: String,
    var results: IntArray,
    var nextResults: IntArray?
)