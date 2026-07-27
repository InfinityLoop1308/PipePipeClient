package org.schabi.newpipe.player.datasource

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Collections
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrDecodedResponse
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrProtocolException
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrScriptPolicy
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSessionPolicy

class QuickJsSabrSessionPolicyTest {
    @Test
    fun changesRequestsBackoffAndMediaProtocol() {
        val policy = QuickJsSabrSessionPolicy(script(POLICY))
        val state = SabrSessionPolicy.State(1, 0, 0, 0)

        val request = policy.evaluate(
            state,
            SabrSessionPolicy.RequestEvent(0, 0, 0, 7, byteArrayOf(1, 2, 3)),
        )
        assertArrayEquals(byteArrayOf(1, 7), request.requestBody)

        val response = policy.evaluate(
            state,
            SabrSessionPolicy.ControlResponseEvent(
                0,
                true,
                SabrSessionPolicy.ControlMode.PUMP,
                SabrDecodedResponse(),
            ),
        )
        assertEquals(SabrSessionPolicy.ActionType.SLEEP_BACKOFF, response.actions[0].type)
        assertEquals(4_321, response.controlDecision!!.backoffTimeMs)
        assertEquals(2, response.nextState.redirectCount)
        assertEquals(null, response.statePatch)

        val header = policy.mediaProtocol.decodeHeader(
            byteArrayOf(0x08, 0x04, 0x18, 0xfb.toByte(), 0x01, 0x48, 0x08),
        )
        assertEquals(4, header.headerId)
        assertEquals(251, header.itag)
        assertEquals(8, header.sequenceNumber)
        policy.close()
    }

    @Test
    fun compiledPoliciesKeepSessionStateIndependent() {
        val script = script(POLICY)
        val first = QuickJsSabrSessionPolicy(script)
        val second = QuickJsSabrSessionPolicy(script)
        val state = SabrSessionPolicy.State(1, 0, 0, 0)
        val event = SabrSessionPolicy.RequestEvent(0, 0, 0, 3, byteArrayOf(1))

        assertArrayEquals(byteArrayOf(1, 3), first.evaluate(state, event).requestBody)
        assertArrayEquals(byteArrayOf(2, 3), first.evaluate(state, event).requestBody)
        assertArrayEquals(byteArrayOf(1, 3), second.evaluate(state, event).requestBody)
        first.close()
        second.close()
    }

    @Test
    fun supportsModernJavaScriptSyntax() {
        val modern = POLICY.replace(
            "headerType: 120",
            "headerType: ({ value: 120 })?.value",
        )

        QuickJsSabrSessionPolicy(script(modern)).use {
            assertEquals(120, it.mediaProtocol.headerPartType)
        }
    }

    @Test
    fun parsesNormalizedResponseStatePatch() {
        val source = POLICY.replace(
            "actions:['SLEEP_BACKOFF'],backoffMs:4321,",
            "actions:['APPLY_RESPONSE_STATE','SLEEP_BACKOFF'],backoffMs:4321," +
                "statePatch:{nextRequest:{targetAudioReadaheadMs:9000}," +
                "live:[{headSequenceNumber:77}]," +
                "formats:[{itag:251,endSegmentNumber:99}]," +
                "contexts:[{type:4,scope:1,value:'AQ==',sendByDefault:true," +
                "writePolicy:1}],contextPolicy:{start:[4],stop:[],discard:[]}},",
        )
        val policy = QuickJsSabrSessionPolicy(script(source))
        val result = policy.evaluate(
            SabrSessionPolicy.State(1, 0, 0, 0),
            SabrSessionPolicy.ControlResponseEvent(
                0,
                true,
                SabrSessionPolicy.ControlMode.PUMP,
                SabrDecodedResponse(),
            ),
        )

        assertEquals(SabrSessionPolicy.ActionType.APPLY_RESPONSE_STATE, result.actions[0].type)
        requireNotNull(result.statePatch)
        policy.close()
    }

    @Test
    fun exposesDemandRouteAndReturnedSegmentIdentities() {
        val policy = QuickJsSabrSessionPolicy(script(DEMAND_POLICY))
        val state = SabrSessionPolicy.DemandState(1_000, 1_250, 1, 0)

        assertEquals(
            SabrSessionPolicy.DemandRoute.RECOVER_MISSING,
            policy.evaluateDemandRoute(
                SabrSessionPolicy.DemandRouteEvent(251, 7, 30_000, 25_000, state),
            ),
        )
        val decision = policy.evaluateDemandResponse(
            SabrSessionPolicy.DemandResponseEvent(
                251,
                7,
                30_000,
                25_000,
                state,
                1,
                0,
                Collections.singletonList(
                    SabrSessionPolicy.DemandReturnedSegment(398, 9, 30_000, 5_000),
                ),
                false,
            ),
        )

        assertEquals(SabrSessionPolicy.DemandOutcome.CONTINUE, decision.outcome)
        assertEquals(321, decision.retryDelayMs)
        policy.close()
    }

    @Test
    fun rejectsMissingPolicyFactory() {
        assertThrows(SabrProtocolException::class.java) {
            QuickJsSabrSessionPolicy(script("var unrelated = true;"))
        }
    }

    @Test
    fun reusesRuntimeAcrossManySessions() {
        val script = script(POLICY)
        repeat(200) {
            QuickJsSabrSessionPolicy(script).close()
        }
    }

    private fun script(source: String): SabrScriptPolicy {
        val now = System.currentTimeMillis()
        return SabrScriptPolicy(10_000, now - 1_000, now + 60_000, source)
    }

    private companion object {
        const val POLICY =
            "function createSabrPolicy(sabr){var count=0;return{" +
                "describe:function(){return{media:{headerType: 120,mediaType:121,endType:122," +
                "headerDecoder:'js'}}}," +
                "initialRequest:function(e){return{body:sabr.base64.encode([++count,e.bufferedRangeCount])}}," +
                "followUpRequest:function(e){return{body:sabr.base64.encode([++count,e.bufferedRangeCount])}}," +
                "response:function(e){return{actions:['SLEEP_BACKOFF'],backoffMs:4321," +
                "state:{redirectCount:2}}}," +
                "mediaHeader:function(e){var f=sabr.proto.decode(sabr.base64.decode(e.data)),r={};" +
                "for(var i=0;i<f.length;i++){if(f[i].n===1)r.headerId=f[i].v;" +
                "if(f[i].n===3)r.itag=f[i].v;if(f[i].n===9)r.sequenceNumber=f[i].v}return r}" +
                "}}"

        const val DEMAND_POLICY =
            "function createSabrPolicy(sabr){return{" +
                "describe:function(){return{demand:true,media:{headerType:20,mediaType:21," +
                "endType:22,headerDecoder:'builtin'}}}," +
                "initialRequest:function(e){return{body:e.fallbackBody}}," +
                "followUpRequest:function(e){return{body:e.fallbackBody}}," +
                "response:function(e){return{actions:['CONTINUE']}}," +
                "demandRoute:function(e){return{route:" +
                "e.responsesWithoutDemandedSegment>e.recoveryCount" +
                "?'RECOVER_MISSING':'STREAM'}}," +
                "demandResponse:function(e){if(e.targetItag!==251||" +
                "e.targetSequenceNumber!==7||e.elapsedMs!==250||" +
                "e.returnedSegments.length!==1||e.returnedSegments[0].itag!==398||" +
                "e.returnedSegments[0].sequenceNumber!==9){throw new Error('bad demand event')}" +
                "return{outcome:'CONTINUE',retryDelayMs:321}}" +
                "}}"
    }
}
