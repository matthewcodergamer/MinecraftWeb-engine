package io.netty.buffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Plain indexed primitive accessors for TeaVM. The receiver is intentionally unused:
 * VarHandleByteBufferAccess methods are package-private instance methods in Netty 4.2.
 */
public final class NettyVarHandlePatches {
    private NettyVarHandlePatches() {}
    private static ByteBuffer view(ByteBuffer b, ByteOrder order) { return b.duplicate().order(order); }
    public static short getShortBE(VarHandleByteBufferAccess self, ByteBuffer b,int i){return view(b,ByteOrder.BIG_ENDIAN).getShort(i);}
    public static void setShortBE(VarHandleByteBufferAccess self, ByteBuffer b,int i,int v){view(b,ByteOrder.BIG_ENDIAN).putShort(i,(short)v);}
    public static short getShortLE(VarHandleByteBufferAccess self, ByteBuffer b,int i){return view(b,ByteOrder.LITTLE_ENDIAN).getShort(i);}
    public static void setShortLE(VarHandleByteBufferAccess self, ByteBuffer b,int i,int v){view(b,ByteOrder.LITTLE_ENDIAN).putShort(i,(short)v);}
    public static int getIntBE(VarHandleByteBufferAccess self, ByteBuffer b,int i){return view(b,ByteOrder.BIG_ENDIAN).getInt(i);}
    public static void setIntBE(VarHandleByteBufferAccess self, ByteBuffer b,int i,int v){view(b,ByteOrder.BIG_ENDIAN).putInt(i,v);}
    public static int getIntLE(VarHandleByteBufferAccess self, ByteBuffer b,int i){return view(b,ByteOrder.LITTLE_ENDIAN).getInt(i);}
    public static void setIntLE(VarHandleByteBufferAccess self, ByteBuffer b,int i,int v){view(b,ByteOrder.LITTLE_ENDIAN).putInt(i,v);}
    public static long getLongBE(VarHandleByteBufferAccess self, ByteBuffer b,int i){return view(b,ByteOrder.BIG_ENDIAN).getLong(i);}
    public static void setLongBE(VarHandleByteBufferAccess self, ByteBuffer b,int i,long v){view(b,ByteOrder.BIG_ENDIAN).putLong(i,v);}
    public static long getLongLE(VarHandleByteBufferAccess self, ByteBuffer b,int i){return view(b,ByteOrder.LITTLE_ENDIAN).getLong(i);}
    public static void setLongLE(VarHandleByteBufferAccess self, ByteBuffer b,int i,long v){view(b,ByteOrder.LITTLE_ENDIAN).putLong(i,v);}
    public static short getShortBE(VarHandleByteBufferAccess self, byte[] b,int i){return (short)(((b[i]&255)<<8)|(b[i+1]&255));}
    public static void setShortBE(VarHandleByteBufferAccess self, byte[] b,int i,int v){b[i]=(byte)(v>>>8);b[i+1]=(byte)v;}
    public static short getShortLE(VarHandleByteBufferAccess self, byte[] b,int i){return (short)((b[i]&255)|((b[i+1]&255)<<8));}
    public static void setShortLE(VarHandleByteBufferAccess self, byte[] b,int i,int v){b[i]=(byte)v;b[i+1]=(byte)(v>>>8);}
    public static int getIntBE(VarHandleByteBufferAccess self, byte[] b,int i){return ((b[i]&255)<<24)|((b[i+1]&255)<<16)|((b[i+2]&255)<<8)|(b[i+3]&255);}
    public static void setIntBE(VarHandleByteBufferAccess self, byte[] b,int i,int v){b[i]=(byte)(v>>>24);b[i+1]=(byte)(v>>>16);b[i+2]=(byte)(v>>>8);b[i+3]=(byte)v;}
    public static int getIntLE(VarHandleByteBufferAccess self, byte[] b,int i){return (b[i]&255)|((b[i+1]&255)<<8)|((b[i+2]&255)<<16)|(b[i+3]<<24);}
    public static void setIntLE(VarHandleByteBufferAccess self, byte[] b,int i,int v){b[i]=(byte)v;b[i+1]=(byte)(v>>>8);b[i+2]=(byte)(v>>>16);b[i+3]=(byte)(v>>>24);}
    public static long getLongBE(VarHandleByteBufferAccess self, byte[] b,int i){return ((long)(b[i]&255)<<56)|((long)(b[i+1]&255)<<48)|((long)(b[i+2]&255)<<40)|((long)(b[i+3]&255)<<32)|((long)(b[i+4]&255)<<24)|((long)(b[i+5]&255)<<16)|((long)(b[i+6]&255)<<8)|(long)(b[i+7]&255);}
    public static void setLongBE(VarHandleByteBufferAccess self, byte[] b,int i,long v){for(int n=0;n<8;n++)b[i+n]=(byte)(v>>>(56-8*n));}
    public static long getLongLE(VarHandleByteBufferAccess self, byte[] b,int i){return ((long)(b[i]&255))|((long)(b[i+1]&255)<<8)|((long)(b[i+2]&255)<<16)|((long)(b[i+3]&255)<<24)|((long)(b[i+4]&255)<<32)|((long)(b[i+5]&255)<<40)|((long)(b[i+6]&255)<<48)|((long)(b[i+7]&255)<<56);}
    public static void setLongLE(VarHandleByteBufferAccess self, byte[] b,int i,long v){for(int n=0;n<8;n++)b[i+n]=(byte)(v>>>(8*n));}
}
