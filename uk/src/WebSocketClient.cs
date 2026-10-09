using System;
using System.IO;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;

namespace UKPassthrough
{
    /// <summary>
    /// A small RFC 6455 WebSocket client over a TcpClient: text and binary messages, fragments, ping/pong and
    /// close. Enough for a link on 127.0.0.1, without shipping a library next to the plugin.
    /// </summary>
    internal sealed class WebSocketClient : IDisposable
    {
        public const int Text = 0x1;
        public const int Binary = 0x2;
        private const int Continuation = 0x0;
        private const int Close = 0x8;
        private const int Ping = 0x9;
        private const int Pong = 0xA;
        private const string Guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

        private readonly TcpClient tcp;
        private readonly NetworkStream stream;
        private readonly object writeLock = new object();
        private readonly Random random = new Random();
        private readonly byte[] header = new byte[14];

        private WebSocketClient(TcpClient tcp)
        {
            this.tcp = tcp;
            this.stream = tcp.GetStream();
        }

        /// <summary>Connects and completes the handshake, or throws.</summary>
        public static WebSocketClient Connect(string host, int port, int timeoutMs)
        {
            var tcp = new TcpClient { NoDelay = true, ReceiveBufferSize = 1 << 20, SendBufferSize = 1 << 20 };
            try
            {
                IAsyncResult pending = tcp.BeginConnect(host, port, null, null);
                if (!pending.AsyncWaitHandle.WaitOne(timeoutMs) || !tcp.Connected)
                {
                    throw new IOException("no answer on " + host + ":" + port);
                }

                tcp.EndConnect(pending);
                var ws = new WebSocketClient(tcp);
                ws.Handshake(host, port, timeoutMs);
                return ws;
            }
            catch
            {
                tcp.Close();
                throw;
            }
        }

        private void Handshake(string host, int port, int timeoutMs)
        {
            var nonce = new byte[16];
            this.random.NextBytes(nonce);
            string key = Convert.ToBase64String(nonce);
            string request = "GET / HTTP/1.1\r\n" +
                "Host: " + host + ":" + port + "\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: " + key + "\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n";
            byte[] bytes = Encoding.ASCII.GetBytes(request);
            this.stream.Write(bytes, 0, bytes.Length);

            // the response headers, up to the blank line
            this.tcp.ReceiveTimeout = timeoutMs;
            var response = new StringBuilder();
            while (!response.ToString().EndsWith("\r\n\r\n", StringComparison.Ordinal))
            {
                int b = this.stream.ReadByte();
                if (b < 0 || response.Length > 8192)
                {
                    throw new IOException("bad handshake response");
                }

                response.Append((char)b);
            }

            this.tcp.ReceiveTimeout = 0;
            string text = response.ToString();
            if (!text.StartsWith("HTTP/1.1 101", StringComparison.Ordinal))
            {
                throw new IOException("handshake refused: " + text.Split('\r')[0]);
            }

            string expected;
            using (SHA1 sha1 = SHA1.Create())
            {
                expected = Convert.ToBase64String(sha1.ComputeHash(Encoding.ASCII.GetBytes(key + Guid)));
            }

            if (text.IndexOf("Sec-WebSocket-Accept: " + expected, StringComparison.OrdinalIgnoreCase) < 0)
            {
                throw new IOException("handshake accept key doesn't match");
            }
        }

        public void SendText(string message) => this.SendFrame(Text, Encoding.UTF8.GetBytes(message));

        public void SendBinary(byte[] payload) => this.SendFrame(Binary, payload);

        /// <summary>Sends one unfragmented frame. Client frames are always masked. Any thread.</summary>
        private void SendFrame(int opcode, byte[] payload)
        {
            lock (this.writeLock)
            {
                int n = 0;
                this.header[n++] = (byte)(0x80 | opcode);
                if (payload.Length < 126)
                {
                    this.header[n++] = (byte)(0x80 | payload.Length);
                }
                else if (payload.Length <= ushort.MaxValue)
                {
                    this.header[n++] = 0x80 | 126;
                    this.header[n++] = (byte)(payload.Length >> 8);
                    this.header[n++] = (byte)payload.Length;
                }
                else
                {
                    this.header[n++] = 0x80 | 127;
                    long length = payload.Length;
                    for (int i = 7; i >= 0; i--)
                    {
                        this.header[n++] = (byte)(length >> (8 * i));
                    }
                }

                var mask = new byte[4];
                this.random.NextBytes(mask);
                Array.Copy(mask, 0, this.header, n, 4);
                n += 4;
                var masked = new byte[payload.Length];
                for (int i = 0; i < payload.Length; i++)
                {
                    masked[i] = (byte)(payload[i] ^ mask[i & 3]);
                }

                this.stream.Write(this.header, 0, n);
                this.stream.Write(masked, 0, masked.Length);
            }
        }

        /// <summary>
        /// Blocks until a whole text or binary message arrives (answering pings on the way). Returns false when
        /// the server closes the link.
        /// </summary>
        public bool Receive(out int opcode, out byte[] payload)
        {
            opcode = 0;
            payload = null;
            MemoryStream fragments = null;
            while (true)
            {
                int b0 = this.ReadByteOrThrow();
                int b1 = this.ReadByteOrThrow();
                bool fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0F;
                long length = b1 & 0x7F;
                if (length == 126)
                {
                    length = (this.ReadByteOrThrow() << 8) | this.ReadByteOrThrow();
                }
                else if (length == 127)
                {
                    length = 0;
                    for (int i = 0; i < 8; i++)
                    {
                        length = (length << 8) | (uint)this.ReadByteOrThrow();
                    }
                }

                byte[] mask = null;
                if ((b1 & 0x80) != 0)
                {
                    mask = this.ReadExactly(4);
                }

                if (length > 256L << 20)
                {
                    throw new IOException("message too large: " + length);
                }

                byte[] data = this.ReadExactly((int)length);
                if (mask != null)
                {
                    for (int i = 0; i < data.Length; i++)
                    {
                        data[i] ^= mask[i & 3];
                    }
                }

                switch (op)
                {
                    case Ping:
                        this.SendFrame(Pong, data);
                        continue;
                    case Pong:
                        continue;
                    case Close:
                        return false;
                    case Text:
                    case Binary:
                        if (fin)
                        {
                            opcode = op;
                            payload = data;
                            return true;
                        }

                        fragments = new MemoryStream();
                        fragments.Write(data, 0, data.Length);
                        opcode = op;
                        continue;
                    case Continuation:
                        if (fragments == null)
                        {
                            throw new IOException("continuation without a first fragment");
                        }

                        fragments.Write(data, 0, data.Length);
                        if (fin)
                        {
                            payload = fragments.ToArray();
                            return true;
                        }

                        continue;
                    default:
                        throw new IOException("unknown opcode " + op);
                }
            }
        }

        private int ReadByteOrThrow()
        {
            int b = this.stream.ReadByte();
            if (b < 0)
            {
                throw new EndOfStreamException("link closed");
            }

            return b;
        }

        private byte[] ReadExactly(int count)
        {
            var data = new byte[count];
            int read = 0;
            while (read < count)
            {
                int n = this.stream.Read(data, read, count - read);
                if (n <= 0)
                {
                    throw new EndOfStreamException("link closed");
                }

                read += n;
            }

            return data;
        }

        public void Dispose()
        {
            try
            {
                lock (this.writeLock)
                {
                    // a close frame with no status, then the socket
                    this.stream.Write(new byte[] { 0x80 | Close, 0x80, 0, 0, 0, 0 }, 0, 6);
                }
            }
            catch (Exception)
            {
                // already gone
            }

            this.tcp.Close();
        }
    }
}
