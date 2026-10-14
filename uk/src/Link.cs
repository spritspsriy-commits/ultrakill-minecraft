using System;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text;
using System.Threading;
using Newtonsoft.Json.Linq;

namespace UKPassthrough
{
    /// <summary>
    /// The link to Minecraft. A background thread keeps a WebSocket connection to the Fabric mod on
    /// 127.0.0.1 (reconnecting every second while Minecraft isn't there). Received messages queue up for the
    /// main thread; sends go out on a writer thread, so the game never waits on the socket.
    /// </summary>
    internal sealed class Link : IDisposable
    {
        public const int Protocol = 30;
        private const int TimeoutMs = 3000;

        private readonly string host;
        private readonly int port;
        private readonly string hello;
        private readonly Thread thread;
        /// <summary>JObjects (text messages) and SectionData (binary block messages, parsed here).</summary>
        private readonly ConcurrentQueue<object> inbox = new ConcurrentQueue<object>();
        private readonly ConcurrentQueue<string> outbox = new ConcurrentQueue<string>();
        private readonly AutoResetEvent outboxSignal = new AutoResetEvent(false);
        private readonly Stopwatch clock = Stopwatch.StartNew();
        private volatile bool running = true;
        private volatile WebSocketClient socket;
        private long lastHeardMs;

        /// <param name="hello">sent first on every connection (built on the main thread: Unity's API isn't thread-safe)</param>
        public Link(string host, int port, JObject hello)
        {
            this.host = host;
            this.port = port;
            this.hello = hello.ToString(Newtonsoft.Json.Formatting.None);
            this.thread = new Thread(this.Run) { IsBackground = true, Name = "UKPassthrough link" };
            this.thread.Start();
        }

        public string Endpoint => this.host + ":" + this.port;

        /// <summary>Gets {"t":"in"} messages on the link thread, skipping the main-thread queue (input latency).</summary>
        public Action<JObject> OnInput { get; set; }

        /// <summary>Milliseconds on the link's clock (used for ping timestamps).</summary>
        public double NowMs => this.clock.Elapsed.TotalMilliseconds;

        public bool Connected => this.socket != null && this.clock.ElapsedMilliseconds - Interlocked.Read(ref this.lastHeardMs) < TimeoutMs;

        /// <summary>Queues a message for Minecraft; dropped while not connected.</summary>
        public void Send(JObject message)
        {
            if (this.socket == null)
            {
                return;
            }

            this.outbox.Enqueue(message.ToString(Newtonsoft.Json.Formatting.None));
            this.outboxSignal.Set();
        }

        /// <summary>The next received message (a JObject or a SectionData), or null. Main thread.</summary>
        public object Poll() => this.inbox.TryDequeue(out object m) ? m : null;

        private void Run()
        {
            while (this.running)
            {
                WebSocketClient ws;
                try
                {
                    ws = WebSocketClient.Connect(this.host, this.port, 1000);
                }
                catch (Exception)
                {
                    // Minecraft isn't running yet (or the mod isn't loaded): try again in a second
                    Thread.Sleep(1000);
                    continue;
                }

                Plugin.Log.LogInfo("connected to Minecraft on " + this.Endpoint);
                while (this.outbox.TryDequeue(out _))
                {
                    // nothing queued before this connection is meant for it
                }

                Interlocked.Exchange(ref this.lastHeardMs, this.clock.ElapsedMilliseconds);
                this.socket = ws;
                var writer = new Thread(() => this.Write(ws)) { IsBackground = true, Name = "UKPassthrough link writer" };
                writer.Start();
                this.outbox.Enqueue(this.hello);
                this.outboxSignal.Set();
                try
                {
                    while (this.running && ws.Receive(out int opcode, out byte[] payload))
                    {
                        Interlocked.Exchange(ref this.lastHeardMs, this.clock.ElapsedMilliseconds);
                        if (opcode == WebSocketClient.Binary)
                        {
                            // block sections: parsed here so the game's frame only has to store them
                            if (payload.Length > 0 && payload[0] == SectionData.TypeSection)
                            {
                                try
                                {
                                    this.inbox.Enqueue(SectionData.Parse(payload));
                                }
                                catch (Exception e)
                                {
                                    Plugin.Log.LogWarning("bad section message: " + e.Message);
                                }
                            }

                            continue;
                        }

                        JObject m = JObject.Parse(Encoding.UTF8.GetString(payload));
                        if ((string)m["t"] == "in" && this.OnInput != null)
                        {
                            this.OnInput(m);
                            continue;
                        }

                        if ((string)m["t"] == "ping")
                        {
                            // answered here, not on the main thread: a long scene load mustn't look like a dead link
                            this.Send(new JObject { ["t"] = "pong", ["id"] = m["id"], ["ts"] = m["ts"] });
                            continue;
                        }

                        this.inbox.Enqueue(m);
                    }

                    if (this.running)
                    {
                        Plugin.Log.LogInfo("Minecraft closed the link");
                    }
                }
                catch (Exception e)
                {
                    if (this.running)
                    {
                        Plugin.Log.LogInfo("Minecraft link closed: " + e.Message);
                    }
                }

                this.socket = null;
                this.outboxSignal.Set();
                ws.Dispose();
                this.inbox.Enqueue(new JObject { ["t"] = "_closed" });
                if (this.running)
                {
                    Thread.Sleep(1000);
                }
            }
        }

        private void Write(WebSocketClient ws)
        {
            try
            {
                while (this.running && this.socket == ws)
                {
                    this.outboxSignal.WaitOne(250);
                    while (this.socket == ws && this.outbox.TryDequeue(out string message))
                    {
                        ws.SendText(message);
                    }
                }
            }
            catch (Exception e)
            {
                Plugin.Log.LogInfo("Minecraft link write failed: " + e.Message);
                ws.Dispose();
            }
        }

        /// <summary>Drops the current connection (it reconnects by itself), e.g. after the link went quiet.</summary>
        public void Drop()
        {
            this.socket?.Dispose();
        }

        public void Dispose()
        {
            this.running = false;
            this.outboxSignal.Set();
            this.socket?.Dispose();
        }
    }
}
