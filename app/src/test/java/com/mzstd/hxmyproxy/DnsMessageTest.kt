package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.proxy.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.DataOutputStream
import java.util.Base64

internal object DnsTestWire {
    fun query(host:String="qa.test",type:Int=1,id:Int=0x1234,udpSize:Int?=1232):ByteArray {
        val bytes=ByteArrayOutputStream();val out=DataOutputStream(bytes)
        out.writeShort(id);out.writeShort(0x0110);out.writeShort(1);out.writeShort(0);out.writeShort(0);out.writeShort(if(udpSize==null)0 else 1)
        if(host!=".")for(label in host.split('.')){val b=label.toByteArray();out.writeByte(b.size);out.write(b)}
        out.writeByte(0);out.writeShort(type);out.writeShort(1)
        if(udpSize!=null){out.writeByte(0);out.writeShort(41);out.writeShort(udpSize);out.writeInt(0x8000);out.writeShort(0)}
        return bytes.toByteArray()
    }
    fun answer(query:ByteArray,payload:ByteArray=byteArrayOf(1,2,3,4),rcode:Int=0,extendedRcode:Int=0):ByteArray {
        val q=DnsMessage.query(query);val bytes=ByteArrayOutputStream();val out=DataOutputStream(bytes)
        out.writeShort(q.id);out.writeShort(0x81b0 or rcode);out.writeShort(1);out.writeShort(if(payload.isEmpty())0 else 1);out.writeShort(0);out.writeShort(if(q.opt==null)0 else 1)
        out.write(query,12,q.end-12)
        if(payload.isNotEmpty()){out.writeShort(0xc00c);out.writeShort(q.type);out.writeShort(q.clazz);out.writeInt(60);out.writeShort(payload.size);out.write(payload)}
        if(q.opt!=null){out.writeByte(0);out.writeShort(41);out.writeShort(q.opt.payloadSize);out.writeInt((extendedRcode shl 24) or 0x8000);out.writeShort(0)}
        return bytes.toByteArray()
    }
}

class DnsMessageTest {
    @Test fun commonRecordTypesEdnsAndNegativeRepliesAreOpaqueAndKeepIds() {
        for(type in listOf(1,28,16,33,64,65)) {
            val query=DnsTestWire.query(type=type);val q=DnsMessage.query(query)
            val answer=DnsTestWire.answer(query,ByteArray(128){(it%251).toByte()})
            assertEquals("qa.test",q.ruleHost);assertEquals(1232,q.udpBudget)
            assertArrayEquals(answer,DnsMessage.forTransport(answer,q,false));assertArrayEquals(answer,DnsMessage.forTransport(answer,q,true))
            assertEquals(type,DnsMessage.response(answer,q).type)
        }
        val query=DnsTestWire.query();val q=DnsMessage.query(query)
        val negative=DnsTestWire.answer(query,ByteArray(0),3)
        assertEquals(3,DnsMessage.response(negative,q).flags and 15)
        val refused=DnsMessage.refused(q);assertEquals(5,DnsMessage.response(refused,q).flags and 15)
    }

    @Test fun udpTruncationBuildsAValidMessageAndKeepsExtendedRcodeWithoutSlicingRecords() {
        val wire=DnsTestWire.query(type=16,udpSize=512);val q=DnsMessage.query(wire)
        val full=DnsTestWire.answer(wire,ByteArray(4000){42},extendedRcode=1)
        assertArrayEquals(full,DnsMessage.forTransport(full,q,false))
        val shortened=DnsMessage.forTransport(full,q,true);val parsed=DnsMessage.response(shortened,q)
        assertTrue(shortened.size<=512);assertTrue(parsed.flags and 0x200!=0);assertEquals(0,parsed.flags and 0x20)
        assertEquals(1,parsed.opt!!.ttl ushr 24);assertEquals(q.id,parsed.id)
        assertEquals(0,shortened[6].toInt());assertEquals(0,shortened[7].toInt())
        assertEquals(512,DnsMessage.query(DnsTestWire.query(udpSize=null)).udpBudget)
        assertEquals(512,DnsMessage.query(DnsTestWire.query(udpSize=1)).udpBudget)
        assertEquals(65507,DnsMessage.query(DnsTestWire.query(udpSize=65535)).udpBudget)
    }

    @Test fun malformedQuestionsCountsEdnsAndCompressionCyclesAreRejected() {
        val good=DnsTestWire.query()
        val bad=listOf(good.copyOf(12),good+byteArrayOf(0),good.copyOf().apply{this[5]=2},good.copyOf().apply{this[2]=0x81.toByte()},
            good.copyOf().apply{this[12]=0xc0.toByte();this[13]=12},good.copyOf().apply{this[12]=0xc0.toByte();this[13]=0},
            good.copyOf().apply{this[12]=64},good.copyOf().apply{this[this.size-1]=4},good.copyOf().apply{this[7]=1})
        for(wire in bad)assertThrows(DnsMessage.Malformed::class.java){DnsMessage.query(wire)}
        val q=DnsMessage.query(good);val mismatched=DnsTestWire.answer(good).apply{this[1]=(this[1]+1).toByte()}
        assertThrows(DnsMessage.Malformed::class.java){DnsMessage.response(mismatched,q)}
    }

    @Test fun rootAndServiceLabelsRemainValidWithoutInterpretingRdata() {
        assertEquals(".",DnsMessage.query(DnsTestWire.query(".",48)).ruleHost)
        assertEquals("_service._tcp.qa.test",DnsMessage.query(DnsTestWire.query("_service._tcp.QA.test",33)).ruleHost)
    }

    @Test fun httpGetPostAndDecodedChunkedBodyUseTheSameWireWithoutPrivateSessionHeaders() {
        val wire=DnsTestWire.query(type=65);val host=listOf("Host" to "phone")
        val get=DnsHttpProtocol.read("GET","/dns-query?dns=${Base64.getUrlEncoder().withoutPadding().encodeToString(wire)}&transport=udp","HTTP/1.1",host,ByteArrayInputStream(ByteArray(0)))
        assertTrue(get.udp);assertArrayEquals(wire,get.wire)
        val post=DnsHttpProtocol.read("POST","/dns-query","HTTP/1.1",host+listOf("Content-Type" to "application/dns-message","Content-Length" to wire.size.toString()),ByteArrayInputStream(wire))
        assertFalse(post.udp);assertArrayEquals(wire,post.wire)
        val chunked="${wire.size.toString(16)}\r\n".toByteArray()+wire+"\r\n0\r\nX-Trailer: ignored\r\n\r\n".toByteArray()
        val decoded=DnsHttpProtocol.read("POST","/dns-query?transport=tcp","HTTP/1.1",host+listOf("Content-Type" to "application/dns-message","Transfer-Encoding" to "chunked"),ByteArrayInputStream(chunked))
        assertArrayEquals(wire,decoded.wire)
    }

    @Test fun ambiguousFramingAndOversizedBodiesFailBeforeResolverWork() {
        val wire=DnsTestWire.query();val base=listOf("Host" to "phone","Content-Type" to "application/dns-message")
        for(headers in listOf(base+listOf("Content-Length" to "12","Content-Length" to "12"),
            base+listOf("Content-Length" to "12","Transfer-Encoding" to "chunked"),base+listOf("Content-Length" to "65536"),
            base+listOf("Content-Length" to "-1")))
            assertThrows(ControlHttpBody.Error::class.java){DnsHttpProtocol.read("POST","/dns-query","HTTP/1.1",headers,ByteArrayInputStream(wire))}
        assertThrows(ControlHttpBody.Error::class.java){DnsHttpProtocol.read("GET","/dns-query?dns=AAAA&dns=BBBB","HTTP/1.1",base,ByteArrayInputStream(wire))}
    }
}
