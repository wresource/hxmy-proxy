package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.proxy.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal object EchoTestWire {
    fun request(v6:Boolean=false,size:Int=64):ByteArray=ByteArray(size){(it%251).toByte()}.apply{
        this[0]=(if(v6)128 else 8).toByte();this[1]=0;this[2]=0;this[3]=0;this[4]=0x12;this[5]=0x34;this[6]=0x43;this[7]=0x21
        if(!v6){val c=EchoProtocol.checksum(this);this[2]=(c ushr 8).toByte();this[3]=c.toByte()}
    }
    fun nativeReply(wire:ByteArray,v6:Boolean=false,id:Int=77)=wire.copyOf().apply{
        this[0]=(if(v6)129 else 0).toByte();this[2]=0;this[3]=0;this[4]=(id ushr 8).toByte();this[5]=id.toByte()
        if(!v6){val c=EchoProtocol.checksum(this);this[2]=(c ushr 8).toByte();this[3]=c.toByte()}else{this[2]=0x12;this[3]=0x34}
    }
}

class EchoMessageTest {
    private val target=InetAddress.getByName("198.18.0.7")
    private class SocketStub(val peer:InetAddress,val respond:Boolean=true):EchoSocket {
        override val identifier=77
        val closed=AtomicBoolean();val sent=AtomicInteger();private var reply:ByteArray?=null
        override fun send(bytes:ByteArray):DatagramSendResult {check(!closed.get());sent.incrementAndGet();if(respond)reply=EchoTestWire.nativeReply(bytes);return DatagramSendResult.SENT}
        override fun receive(buffer:ByteArray):EchoReceived? {val r=reply?:return null;reply=null;r.copyInto(buffer);return EchoReceived(r.size,peer)}
        override fun close(){closed.set(true)}
    }

    @Test fun replyMustMatchTypeCodeIdentifierSequenceAndDataBeforeRestoring() {
        for(v6 in listOf(false,true))for(size in listOf(8,9,1208,65507)) {
            val q=EchoProtocol.Request(InetAddress.getByName(if(v6)"2001:db8::7" else "198.18.0.7"),EchoTestWire.request(v6,size))
            val reply=EchoTestWire.nativeReply(q.wire,v6)
            assertTrue(EchoProtocol.matches(q,reply,77))
            for(index in listOf(0,1,4,6)+(if(size>8)listOf(8)else emptyList())) {
                val changed=reply.copyOf();changed[index]=(changed[index].toInt() xor 1).toByte();assertFalse(EchoProtocol.matches(q,changed,77))
            }
            val restored=EchoProtocol.restore(q,reply);assertEquals(q.id,EchoProtocol.word(restored,4));assertEquals(q.sequence,EchoProtocol.word(restored,6))
            if(v6){assertEquals(0,restored[2].toInt());assertEquals(0,restored[3].toInt())}else assertEquals(0,EchoProtocol.checksum(restored))
        }
    }

    @Test fun controlAcceptsOnlyLiteralTargetsAndEchoRequestsWithinBudget() {
        val wire=EchoTestWire.request();val headers=listOf("Host" to "phone","Content-Type" to "application/octet-stream","Content-Length" to wire.size.toString())
        assertEquals(target,EchoProtocol.read("POST","/icmp-echo?target=198.18.0.7","HTTP/1.1",headers,ByteArrayInputStream(wire)).target)
        for(name in listOf("example.com","198.18.0.0/24","http://198.18.0.7","198.18.0.7&target=198.18.0.8"))
            assertThrows(ControlHttpBody.Error::class.java){EchoProtocol.read("POST","/icmp-echo?target=$name","HTTP/1.1",headers,ByteArrayInputStream(wire))}
        for(type in listOf(0,3,11,128))assertFalse(EchoProtocol.validRequest(target,wire.copyOf().apply{this[0]=type.toByte()}))
        assertFalse(EchoProtocol.validRequest(target,ByteArray(65508)));assertFalse(EchoProtocol.validRequest(target,ByteArray(7)))
    }

    @Test(timeout=10000) fun nativeManagerNormalizesReplyAndDoesNotReplayASilentRequest() = runBlocking {
        val socket=SocketStub(target)
        AndroidEchoMessageQuery(EchoSocketFactory{_,_->socket}).use{backend->
            val request=EchoTestWire.request();val reply=backend.query(null,target,request)
            assertEquals(0,EchoProtocol.checksum(reply));assertEquals(0x1234,EchoProtocol.word(reply,4));assertEquals(1,socket.sent.get())
            assertTrue(ProxyTestKit.await{backend.pendingOperations==0&&socket.closed.get()})
        }
        val silent=SocketStub(target,false)
        AndroidEchoMessageQuery(EchoSocketFactory{_,_->silent}).use{backend->
            assertNull(withTimeoutOrNull(100){backend.query(null,target,EchoTestWire.request())})
            assertTrue(ProxyTestKit.await{backend.pendingOperations==0&&silent.closed.get()});assertEquals(1,silent.sent.get())
        }
    }

    @Test(timeout=15000) fun cancellationRetainsTheBlockedFactoryUntilItsRealSocketCloses() = runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val socket=SocketStub(target)
        val backend=AndroidEchoMessageQuery(EchoSocketFactory{_,_->entered.countDown();check(release.await(5,TimeUnit.SECONDS));socket})
        try{
            val pending=async(Dispatchers.Default){backend.query(null,target,EchoTestWire.request())}
            assertTrue(entered.await(3,TimeUnit.SECONDS));backend.close()
            assertTrue(runCatching{pending.await()}.exceptionOrNull() is CancellationException)
            assertEquals(1,backend.pendingOperations);assertEquals(0,socket.sent.get())
            release.countDown();assertTrue(ProxyTestKit.await{backend.pendingOperations==0&&socket.closed.get()});assertEquals(0,socket.sent.get())
        }finally{release.countDown();backend.close()}
    }

    @Test(timeout=15000) fun fourActualSocketsAndSixteenAdmissionsAreBoundedAcrossClose() = runBlocking {
        val opened=AtomicInteger();val closed=AtomicInteger()
        val backend=AndroidEchoMessageQuery(EchoSocketFactory{_,_->opened.incrementAndGet();object:EchoSocket{
            override val identifier=77;private val ended=AtomicBoolean()
            override fun send(bytes:ByteArray)=DatagramSendResult.SENT
            override fun receive(buffer:ByteArray):EchoReceived?=null
            override fun close(){if(ended.compareAndSet(false,true))closed.incrementAndGet()}
        }})
        try{
            val pending=List(16){async(Dispatchers.Default){runCatching{backend.query(null,target,EchoTestWire.request())}}}
            assertTrue(ProxyTestKit.await{backend.pendingOperations==16&&opened.get()==4})
            val rejected=runCatching{backend.query(null,target,EchoTestWire.request())}.exceptionOrNull()
            assertTrue(rejected is ProxyException&&rejected.error==ProxyError.TooManyConnections)
            backend.close();pending.awaitAll();assertTrue(ProxyTestKit.await{backend.pendingOperations==0&&closed.get()==opened.get()})
            assertEquals(4,opened.get())
        }finally{backend.close()}
    }
}
