package com.mzstd.hxmyproxy.core.proxy

import com.mzstd.hxmyproxy.core.rules.RuleInput
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URLDecoder
import com.mzstd.hxmyproxy.core.proxy.ControlHttpBody.Error

internal object EchoProtocol {
    const val PATH="/icmp-echo"
    const val MAX_MESSAGE=65507
    data class Request(val target:InetAddress,val wire:ByteArray) {
        val v6 get()=target !is Inet4Address
        val id get()=word(wire,4)
        val sequence get()=word(wire,6)
    }
    fun recognizes(target:String)=target==PATH||target.startsWith("$PATH?")
    fun read(method:String,path:String,version:String,headers:List<Pair<String,String>>,input:InputStream):Request {
        if(method!="POST")throw Error(405,"Method Not Allowed")
        if(!ConnectUdpProtocol.validControlHost(version,headers)||'#' in path)throw Error(400,"Bad Request")
        val query=path.substringAfter('?',"")
        if(!query.startsWith("target=")||'&' in query)throw Error(400,"Bad Request")
        val name=try{URLDecoder.decode(query.removePrefix("target="),"UTF-8")}catch(_:IllegalArgumentException){throw Error(400,"Bad Request")}
        val parsed=RuleInput.parse(name)
        if(parsed==null||!parsed.isIp||parsed.fromUrl||parsed.host.any{it in "/%"}||'-' in parsed.host)throw Error(400,"Bad Request")
        val ip=try{InetAddress.getByName(parsed.host)}catch(_:Exception){throw Error(400,"Bad Request")}
        val wire=ControlHttpBody.read(headers,input,"application/octet-stream",MAX_MESSAGE)
        if(!validRequest(ip,wire))throw Error(400,"Bad Request")
        return Request(ip,wire)
    }
    fun validRequest(target:InetAddress,wire:ByteArray)=wire.size in 8..MAX_MESSAGE&&
        (wire[0].toInt() and 255)==(if(target is Inet4Address)8 else 128)&&wire[1]==0.toByte()
    fun word(wire:ByteArray,at:Int)=((wire[at].toInt() and 255) shl 8) or (wire[at+1].toInt() and 255)
    fun checksum(bytes:ByteArray):Int {
        var sum=0L;var at=0
        while(at<bytes.size){sum+=((bytes[at].toInt() and 255) shl 8) or (if(at+1<bytes.size)bytes[at+1].toInt() and 255 else 0);at+=2}
        while(sum ushr 16!=0L)sum=(sum and 65535)+(sum ushr 16)
        return sum.inv().toInt() and 65535
    }
    fun matches(request:Request,reply:ByteArray,identifier:Int,length:Int=reply.size):Boolean =
        length==request.wire.size&&reply.size>=length&&(reply[0].toInt() and 255)==(if(request.v6)129 else 0)&&reply[1]==0.toByte()&&
            word(reply,4)==identifier&&word(reply,6)==request.sequence&&
            (8 until length).all{reply[it]==request.wire[it]}

    /** IPv6 checksum needs the original client's IP pseudo-header; callers must recompute it. */
    fun restore(request:Request,reply:ByteArray):ByteArray=reply.copyOf().also {
        it[4]=request.wire[4];it[5]=request.wire[5];it[2]=0;it[3]=0
        if(!request.v6){val c=checksum(it);it[2]=(c ushr 8).toByte();it[3]=c.toByte()}
    }
}
