/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.sql.pg;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.akiban.util.Tap;


/**
 * Basic implementation of Postgres wire protocol for SQL integration.
 *
 * See http://developer.postgresql.org/pgdocs/postgres/protocol.html
 */
public class PostgresMessenger implements DataInput, DataOutput
{
    /*** Message Formats ***/
    public static final int VERSION_CANCEL = 80877102; // 12345678
    public static final int VERSION_SSL = 80877103; // 12345679
    
    public static final int AUTHENTICATION_OK = 0;
    public static final int AUTHENTICATION_KERBEROS_V5 = 2;
    public static final int AUTHENTICATION_CLEAR_TEXT = 3;
    public static final int AUTHENTICATION_MD5 = 5;
    public static final int AUTHENTICATION_SCM = 6;
    public static final int AUTHENTICATION_GSS = 7;
    public static final int AUTHENTICATION_SSPI = 9;
    public static final int AUTHENTICATION_GSS_CONTINUE = 8;

    private final static Tap.InOutTap waitTap = Tap.createTimer("sql: msg: wait");
    private final static Tap.InOutTap recvTap = Tap.createTimer("sql: msg: recv");
    private final static Tap.InOutTap xmitTap = Tap.createTimer("sql: msg: xmit");

    private InputStream inputStream;
    private OutputStream outputStream;
    private DataInputStream dataInput;
    private DataInputStream messageInput;
    private ByteArrayOutputStream byteOutput;
    private DataOutputStream messageOutput;
    private String encoding = "ISO-8859-1";
    private boolean cancel = false;

    public PostgresMessenger(InputStream inputStream, OutputStream outputStream) {
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.dataInput = new DataInputStream(inputStream);
    }

    InputStream getInputStream() {
        return inputStream;
    }

    OutputStream getOutputStream() {
        return outputStream;
    }

    /** The encoding used for strings. */
    public String getEncoding() {
        return encoding;
    }
    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    /** Has a cancel been sent? */
    public synchronized boolean isCancel() {
        return cancel;
    }
    /** Mark as cancelled. Cleared at the start of results. 
     * Usually set from a thread running a request just for that purpose. */
    public synchronized void setCancel(boolean cancel) {
        this.cancel = cancel;
    }

    /** Read the next message from the stream, without any type opcode. */
    protected PostgresMessages readMessage() throws IOException {
        return readMessage(true);
    }
    /** Read the next message from the stream, starting with the message type opcode. */
    protected PostgresMessages readMessage(boolean hasType) throws IOException {
        PostgresMessages type;
        int code = -1;
        if (hasType) {
            try {
                waitTap.in();
                code = dataInput.read();
                if (!PostgresMessages.readTypeCorrect(code)) {
                    throw new IOException ("Bad protocol read message: " + (char)code);
                }
                type = PostgresMessages.messageType(code);
            }
            finally {
                waitTap.out();
            }
        }
        else {
            type = PostgresMessages.STARTUP_MESSAGE_TYPE;
            code = 0;
        }
            
        if (code < 0) 
            return PostgresMessages.EOF_TYPE;                            // EOF
        try {
            recvTap.in();
            int len = dataInput.readInt();
            if ((len < 0) || (len > type.maxSize()))
                throw new IOException(String.format("Implausible message length (%d) received.", len));
            len -= 4;
            try {
                byte[] msg = new byte[len];
                dataInput.readFully(msg, 0, len);
                messageInput = new DataInputStream(new ByteArrayInputStream(msg));
            } catch (OutOfMemoryError ex) {
                throw new IOException (String.format("Unable to allocate read buffer of length (%d)", len));
            }
            return type;
        }
        finally {
            recvTap.out();
        }
    }

    /** Begin outgoing message of given type. */
    protected void beginMessage(int type) throws IOException {
        byteOutput = new ByteArrayOutputStream();
        messageOutput = new DataOutputStream(byteOutput);
        messageOutput.write(type);
        messageOutput.writeInt(0);
    }

    /** Send outgoing message. */
    protected void sendMessage() throws IOException {
        messageOutput.flush();
        byte[] msg = byteOutput.toByteArray();
        
        // check we're writing an allowed message. 
        assert PostgresMessages.writeTypeCorrect((int)msg[0]) : "Invalid write message: " + (char)msg[0];
        
        int len = msg.length - 1;
        msg[1] = (byte)(len >> 24);
        msg[2] = (byte)(len >> 16);
        msg[3] = (byte)(len >> 8);
        msg[4] = (byte)len;
        outputStream.write(msg);
    }

    /** Send outgoing message and optionally flush stream. */
    protected void sendMessage(boolean flush) throws IOException {
        sendMessage();
        if (flush)
            flush();
    }

    protected void flush() throws IOException {
        try {
            xmitTap.in();
            outputStream.flush();
        }
        finally {
            xmitTap.out();
        }
    }

    /** Read null-terminated string. */
    public String readString() throws IOException {
        ByteArrayOutputStream bs = new ByteArrayOutputStream();
        while (true) {
            int b = messageInput.read();
            if (b < 0) throw new IOException("EOF in the middle of a string");
            if (b == 0) break;
            bs.write(b);
        }
        return bs.toString(encoding);
    }

    /** Write null-terminated string. */
    public void writeString(String s) throws IOException {
        byte[] ba = s.getBytes(encoding);
        messageOutput.write(ba);
        messageOutput.write(0);
    }

    /*** DataInput ***/
    public boolean readBoolean() throws IOException {
        return messageInput.readBoolean();
    }
    public byte readByte() throws IOException {
        return messageInput.readByte();
    }
    public char readChar() throws IOException {
        return messageInput.readChar();
    }
    public double readDouble() throws IOException {
        return messageInput.readDouble();
    }
    public float readFloat() throws IOException {
        return messageInput.readFloat();
    }
    public void readFully(byte[] b) throws IOException {
        messageInput.readFully(b);
    }
    public void readFully(byte[] b, int off, int len) throws IOException {
        messageInput.readFully(b, off, len);
    }
    public int readInt() throws IOException {
        return messageInput.readInt();
    }
    @SuppressWarnings("deprecation")
    public String readLine() throws IOException {
        return messageInput.readLine();
    }
    public long readLong() throws IOException {
        return messageInput.readLong();
    }
    public short readShort() throws IOException {
        return messageInput.readShort();
    }
    public String readUTF() throws IOException {
        return messageInput.readUTF();
    }
    public int readUnsignedByte() throws IOException {
        return messageInput.readUnsignedByte();
    }
    public int readUnsignedShort() throws IOException {
        return messageInput.readUnsignedShort();
    }
    public int skipBytes(int n) throws IOException {
        return messageInput.skipBytes(n);
    }

    /*** DataOutput ***/
    public void write(byte[] data) throws IOException {
        messageOutput.write(data);
    }
    public void write(byte[] data, int ofs, int len) throws IOException {
        messageOutput.write(data, ofs, len);
    }
    public void write(int v) throws IOException {
        messageOutput.write(v);
    }
    public void writeBoolean(boolean v) throws IOException {
        messageOutput.writeBoolean(v);
    }
    public void writeByte(int v) throws IOException {
        messageOutput.writeByte(v);
    }
    public void writeBytes(String s) throws IOException {
        messageOutput.writeBytes(s);
    }
    public void writeChar(int v) throws IOException {
        messageOutput.writeChar(v);
    }
    public void writeChars(String s) throws IOException {
        messageOutput.writeChars(s);
    }
    public void writeDouble(double v) throws IOException {
        messageOutput.writeDouble(v);
    }
    public void writeFloat(float v) throws IOException {
        messageOutput.writeFloat(v);
    }
    public void writeInt(int v) throws IOException {
        messageOutput.writeInt(v);
    }
    public void writeLong(long v) throws IOException {
        messageOutput.writeLong(v);
    }
    public void writeShort(int v) throws IOException {
        messageOutput.writeShort(v);
    }
    public void writeUTF(String s) throws IOException {
        messageOutput.writeUTF(s);
    }

}
