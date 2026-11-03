package app.opentv.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LayoutClassTest {
    @Test
    fun enum_touchProperties() {
        assertThat(LayoutClass.TV.isTouch).isFalse()
        assertThat(LayoutClass.PHONE.isTouch).isTrue()
        assertThat(LayoutClass.TABLET.isTouch).isTrue()
    }
}

