package test

internal open class BaseSuperSamePackage {
    fun usage1() {
        val derived = DerivedSuperSamePackage()
        derived.foo()
        val i = derived.i
    }
}

internal class DerivedSuperSamePackage internal constructor() : BaseSuperSamePackage() {

    internal fun foo() {

    }

    internal var i = 1
}