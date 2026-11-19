package app.aaps.plugins.automation.actions

import app.aaps.core.interfaces.queue.Callback
import app.aaps.plugins.automation.R
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyDouble
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.skyscreamer.jsonassert.JSONAssert

class ActionSetPpWeightTest : ActionsTestBase() {

    private lateinit var sut: ActionSetPpWeight

    @BeforeEach fun setUp() {
        whenever(rh.gs(R.string.autoisf_pp_weight)).thenReturn("pp weight")
        whenever(rh.gs(R.string.automate_set_pp_weight)).thenReturn("Set pp_ISF_weight to %1\$.3f")
        whenever(rh.gs(R.string.weight_new)).thenReturn("new value")
        whenever(rh.gs(R.string.weight_old)).thenReturn("as before")
        sut = ActionSetPpWeight(injector)
    }

    @Test fun friendlyNameTest() {
        assertThat(sut.friendlyName()).isEqualTo(R.string.autoisf_pp_weight)
    }

    @Test fun shortDescriptionTest() {
        sut.new_weight.value = 0.025
        // The number that follows is formatted in the machine's locale (0.30 or 0,30).
        assertThat(sut.shortDescription()).startsWith("Set pp_ISF_weight to ")
    }

    @Test fun doActionWritesANewWeight() {
        whenever(sp.getDouble("pp_ISF_weight", 0.0)).thenReturn(0.01)
        sut.new_weight.value = 0.025
        sut.doAction(object : Callback() {
            override fun run() {
                assertThat(result.success).isTrue()
                assertThat(result.comment).isEqualTo("new value")
            }
        })
        verify(sp).putDouble("pp_ISF_weight", 0.025)
    }

    @Test fun doActionLeavesAnUnchangedWeight() {
        whenever(sp.getDouble("pp_ISF_weight", 0.0)).thenReturn(0.025)
        sut.new_weight.value = 0.025
        sut.doAction(object : Callback() {
            override fun run() {
                assertThat(result.success).isFalse()
                assertThat(result.comment).isEqualTo("as before")
            }
        })
        verify(sp, never()).putDouble(anyString(), anyDouble())
    }

    @Test fun hasDialogTest() {
        assertThat(sut.hasDialog()).isTrue()
    }

    @Test fun toJSONTest() {
        sut.new_weight.value = 0.025
        JSONAssert.assertEquals("""{"data":{"weight":0.025},"type":"ActionSetPpWeight"}""", sut.toJSON(), true)
    }

    @Test fun fromJSONTest() {
        sut.fromJSON("""{"weight":0.04}""")
        assertThat(sut.new_weight.value).isEqualTo(0.04)
    }

    @Test fun aSavedActionIsRestored() {
        sut.new_weight.value = 0.025
        val restored = sut.instantiate(JSONObject(sut.toJSON()))
        assertThat(restored).isInstanceOf(ActionSetPpWeight::class.java)
        assertThat((restored as ActionSetPpWeight).new_weight.value).isEqualTo(0.025)
    }

    @Test fun iconTest() {
        assertThat(sut.icon()).isEqualTo(R.drawable.ic_pp_weight)
    }
}
