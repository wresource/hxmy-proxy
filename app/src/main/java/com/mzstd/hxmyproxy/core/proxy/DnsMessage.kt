package com.mzstd.hxmyproxy.core.proxy

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

/** DNS envelope validation only. RR payloads stay opaque; the phone's resolver owns DNS. */
internal object DnsMessage {
    const val MAX_BYTES = 65535
    class Malformed : IOException("Invalid DNS message")
    data class Opt(val payloadSize: Int, val ttl: Int)
    data class Question(val id: Int, val labels: List<ByteArray>, val type: Int, val clazz: Int,
                        val end: Int, val flags: Int, val opt: Opt?) {
        val ruleHost: String get() = if(labels.isEmpty()) "." else labels.joinToString(".") { label ->
            buildString { for (byte in label) {
                val n=byte.toInt() and 255
                when {
                    n in 65..90 -> append((n+32).toChar())
                    n in 97..122 || n in 48..57 || n==45 || n==95 -> append(n.toChar())
                    else -> { append('\\');append(n.toString().padStart(3,'0')) }
                }
            } }
        }
        val udpBudget: Int get() = (opt?.payloadSize ?: 512).coerceIn(512,65507)
    }
    private fun word(b:ByteArray,n:Int):Int { if(n<0||n+2>b.size)throw Malformed();return ((b[n].toInt() and 255) shl 8) or (b[n+1].toInt() and 255) }
    private fun int(b:ByteArray,n:Int):Int = (word(b,n) shl 16) or word(b,n+2)
    private data class Name(val end:Int,val labels:List<ByteArray>,val root:Boolean)
    private fun name(b:ByteArray,start:Int,collect:Boolean):Name {
        var at=start;var end=-1;var expanded=1;var steps=0;var root=true
        val labels=ArrayList<ByteArray>()
        while(true) {
            if(++steps>128||at !in b.indices)throw Malformed()
            val n=b[at].toInt() and 255
            if(n==0)return Name(if(end>=0)end else at+1,labels,root)
            if((n and 0xc0)==0xc0) {
                val pointer=word(b,at) and 0x3fff
                // RFC1035 pointers refer to prior names. Also exclude DNS header bytes.
                if(pointer<12||pointer>=at)throw Malformed()
                if(end<0)end=at+2
                at=pointer;continue
            }
            if(n>63||at+1+n>b.size)throw Malformed()
            expanded+=n+1;if(expanded>255)throw Malformed()
            if(collect)labels.add(b.copyOfRange(at+1,at+1+n))
            root=false;at+=n+1
        }
    }
    private fun parse(b:ByteArray,response:Boolean):Question {
        if(b.size !in 17..MAX_BYTES)throw Malformed()
        val flags=word(b,2)
        if(((flags and 0x8000)!=0)!=response || (flags and 0x7800)!=0 || word(b,4)!=1)throw Malformed()
        if(!response&&(word(b,6)!=0||word(b,8)!=0))throw Malformed()
        val qn=name(b,12,true);val type=word(b,qn.end);val clazz=word(b,qn.end+2);var at=qn.end+4
        val answers=word(b,6);val authorities=word(b,8);val additional=word(b,10)
        val records=answers+authorities+additional
        if(records>(b.size-at)/11)throw Malformed()
        var opt:Opt?=null
        repeat(records) { index ->
            val owner=name(b,at,false);at=owner.end
            val rrType=word(b,at);val rrClass=word(b,at+2);val ttl=int(b,at+4);val length=word(b,at+8);at+=10
            if(length>b.size-at)throw Malformed()
            if(rrType==41) {
                if(index<answers+authorities||!owner.root||opt!=null)throw Malformed()
                // EDNS option data is opaque, but its option lengths must fit.
                var cursor=at
                while(cursor<at+length){if(cursor+4>at+length)throw Malformed();val size=word(b,cursor+2);cursor+=4;if(size>at+length-cursor)throw Malformed();cursor+=size}
                opt=Opt(rrClass,ttl)
            }
            at+=length
        }
        if(at!=b.size)throw Malformed()
        return Question(word(b,0),qn.labels,type,clazz,qn.end+4,flags,opt)
    }
    fun query(wire:ByteArray):Question = parse(wire,false)
    fun response(wire:ByteArray,query:Question):Question {
        val answer=parse(wire,true)
        if(answer.id!=query.id||answer.ruleHost!=query.ruleHost||answer.type!=query.type||answer.clazz!=query.clazz)throw Malformed()
        return answer
    }
    private fun minimal(query:Question,flags:Int,opt:Opt?):ByteArray {
        val bytes=ByteArrayOutputStream();val out=DataOutputStream(bytes)
        out.writeShort(query.id);out.writeShort(flags);out.writeShort(1);out.writeShort(0);out.writeShort(0);out.writeShort(if(opt!=null)1 else 0)
        for(label in query.labels){out.writeByte(label.size);out.write(label)}
        out.writeByte(0);out.writeShort(query.type);out.writeShort(query.clazz)
        if(opt!=null){out.writeByte(0);out.writeShort(41);out.writeShort(opt.payloadSize);out.writeInt(opt.ttl);out.writeShort(0)}
        return bytes.toByteArray()
    }
    fun refused(query:Question):ByteArray = minimal(query,
        0x8085 or (query.flags and 0x0110),query.opt?.let { Opt(it.payloadSize,it.ttl and 0x8000) })
    fun forTransport(wire:ByteArray,query:Question,udp:Boolean):ByteArray {
        val answer=response(wire,query)
        if(!udp||wire.size<=query.udpBudget)return wire
        // Whole sections are omitted, never sliced. Keep extended rcode/version in minimal OPT.
        return minimal(query,(answer.flags or 0x0200) and 0xffdf,answer.opt).also {
            check(it.size<=query.udpBudget)
        }
    }
}
