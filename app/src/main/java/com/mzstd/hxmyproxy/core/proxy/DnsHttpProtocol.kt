package com.mzstd.hxmyproxy.core.proxy

import java.io.InputStream
import java.net.URLDecoder
import java.util.Base64
import com.mzstd.hxmyproxy.core.proxy.ControlHttpBody.Error

internal object DnsHttpProtocol {
    const val PATH="/dns-query"
    data class Request(val wire:ByteArray,val udp:Boolean)
    fun recognizes(target:String)=target==PATH||target.startsWith("$PATH?")

    fun read(method:String,target:String,version:String,headers:List<Pair<String,String>>,input:InputStream):Request {
        if(method!="GET"&&method!="POST")throw Error(405,"Method Not Allowed")
        if(!ConnectUdpProtocol.validControlHost(version,headers)||'#' in target)throw Error(400,"Bad Request")
        val params=LinkedHashMap<String,String>()
        val query=target.substringAfter('?',"")
        if(query.isNotEmpty())for(part in query.split('&')) {
            val index=part.indexOf('=');if(index<=0)throw Error(400,"Bad Request")
            val key=part.substring(0,index)
            if(key !in setOf("dns","transport")||params.containsKey(key))throw Error(400,"Bad Request")
            params[key]=try{URLDecoder.decode(part.substring(index+1),"UTF-8")}catch(_:IllegalArgumentException){throw Error(400,"Bad Request")}
        }
        val transport=params["transport"] ?: "tcp"
        if(transport!="udp"&&transport!="tcp")throw Error(400,"Bad Request")
        ControlHttpBody.checkEncoding(headers)
        val wire=if(method=="GET") {
            if(!ConnectUdpProtocol.validControlGet(method,version,headers))throw Error(400,"Bad Request")
            val encoded=params["dns"] ?: throw Error(400,"Bad Request")
            if(encoded.length>(DnsMessage.MAX_BYTES+2)/3*4)throw Error(413,"Content Too Large")
            try{Base64.getUrlDecoder().decode(encoded)}catch(_:IllegalArgumentException){throw Error(400,"Bad Request")}
        }else{
            if(params.containsKey("dns"))throw Error(400,"Bad Request")
            ControlHttpBody.read(headers,input,"application/dns-message",DnsMessage.MAX_BYTES)
        }
        if(wire.size !in 17..DnsMessage.MAX_BYTES)throw Error(400,"Bad Request")
        return Request(wire,transport=="udp")
    }
}
