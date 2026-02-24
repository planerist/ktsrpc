package net.planerist.ktsrpc.gen.tests

import javax.annotation.Nonnull

class JavaClassWithNullables internal constructor(
    @field:Nonnull @get:Nonnull
    @param:Nonnull var name: String,
    @field:Nonnull @get:Nonnull
    @param:Nonnull var results: IntArray,
    var nextResults: IntArray?
)