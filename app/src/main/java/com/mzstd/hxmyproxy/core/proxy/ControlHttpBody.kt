package com.mzstd.hxmyproxy.core.proxy

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Bounded binary POST framing shared by authenticated control endpoints. */
internal object ControlHttpBody {
    class Error(val status:Int,val reason:String):IOException(reason)
    private fun values(headers:List<Pair<String,String>>,key:String)=headers.filter{it.first.equals(key,true)}.map{it.second.trim()}
    fun checkEncoding(headers:List<Pair<String,String>>) {
        val encoded=values(headers,"content-encoding")
        if(encoded.isNotEmpty()&&(encoded.size!=1||!encoded.single().equals("identity",true)))throw Error(415,"Unsupported Media Type")
    }
    fun read(headers:List<Pair<String,String>>,input:InputStream,mediaType:String,maxBytes:Int):ByteArray {
        checkEncoding(headers)
        val type=values(headers,"content-type").singleOrNull()?.substringBefore(';')?.trim()
        if(!type.equals(mediaType,true))throw Error(415,"Unsupported Media Type")
        val lengths=values(headers,"content-length");val transfers=values(headers,"transfer-encoding")
        if(lengths.size>1||transfers.size>1||(lengths.isNotEmpty()&&transfers.isNotEmpty()))throw Error(400,"Bad Request")
        if(transfers.isNotEmpty()) {
            if(!transfers.single().equals("chunked",true))throw Error(400,"Bad Request")
            val out=object:ByteArrayOutputStream(){
                override fun write(bytes:ByteArray,offset:Int,length:Int){if(length>maxBytes-size())throw Error(413,"Content Too Large");super.write(bytes,offset,length)}
                override fun write(value:Int){if(size()>=maxBytes)throw Error(413,"Content Too Large");super.write(value)}
            }
            try{HttpForwarding.copyDecodedChunks(input,out,ByteArray(8192))}catch(e:Error){throw e}catch(_:IOException){throw Error(400,"Bad Request")}
            return out.toByteArray()
        }
        val length=lengths.singleOrNull()?:throw Error(411,"Length Required")
        if(length.isEmpty()||length.any{it !in '0'..'9'})throw Error(400,"Bad Request")
        val size=length.toLongOrNull()?:throw Error(413,"Content Too Large")
        if(size>maxBytes)throw Error(413,"Content Too Large")
        return ByteArray(size.toInt()).also{try{readFully(input,it)}catch(_:IOException){throw Error(400,"Bad Request")}}
    }
}
