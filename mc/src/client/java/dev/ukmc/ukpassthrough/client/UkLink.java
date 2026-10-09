package dev.ukmc.ukpassthrough.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ukmc.ukpassthrough.UKPassthrough;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/**
 * The link to ULTRAKILL: a WebSocket server on 127.0.0.1 (port 25600, or -Dukpassthrough.port) that the
 * BepInEx plugin connects to. JSON text messages, each with a type "t":
 * <ul>
 * <li>{"t":"hello","v":1,"side":"minecraft"|"ultrakill",...}: sent by each side when the link opens</li>
 * <li>{"t":"ping","id":n,"ts":sender clock} / {"t":"pong","id":n,"ts":echoed}: once a second each way</li>
 * <li>{"t":"say","text":"..."}: shown to the player on the other side (the stage 1 test)</li>
 * </ul>
 * The link counts as lost after 3 seconds without any message.
 */
public final class UkLink extends WebSocketServer {
	public static final int PROTOCOL = 28;
	private static final long TIMEOUT_NANOS = 3_000_000_000L;
	private static final long PING_EVERY_NANOS = 1_000_000_000L;
	private static UkLink instance;

	/** The ULTRAKILL plugin's connection (the last one that said hello as "ultrakill"). */
	private volatile WebSocket peer;
	private volatile String peerInfo = "";
	private volatile long lastHeardNanos;
	private volatile double rttMillis = -1.0;
	/** Counts ULTRAKILL connections, so the block stream knows when to start over. */
	private volatile int generation;
	private long nextPingNanos;
	private final AtomicInteger pingIds = new AtomicInteger();
	/** Pings sent by /uk ping, waiting for their pong. */
	private final Map<Integer, DoubleConsumer> waiting = new ConcurrentHashMap<>();

	private UkLink(final int port) {
		super(new InetSocketAddress("127.0.0.1", port));
		this.setReuseAddr(true);
		this.setDaemon(true);
		this.setTcpNoDelay(true);
	}

	static void launch() {
		instance = new UkLink(Integer.getInteger("ukpassthrough.port", 25600));
		instance.start();
	}

	public static UkLink get() {
		return instance;
	}

	public boolean connected() {
		WebSocket p = this.peer;
		return p != null && p.isOpen() && System.nanoTime() - this.lastHeardNanos < TIMEOUT_NANOS;
	}

	public String peerInfo() {
		return this.peerInfo;
	}

	public double rttMillis() {
		return this.rttMillis;
	}

	public int generation() {
		return this.generation;
	}

	/** Sends a JSON message to ULTRAKILL; dropped when it isn't connected. */
	public void send(final JsonObject message) {
		WebSocket p = this.peer;
		if (p != null && p.isOpen()) {
			p.send(message.toString());
		}
	}

	/** Sends a binary message (blocks) to ULTRAKILL; dropped when it isn't connected. */
	public void sendBinary(final byte[] message) {
		WebSocket p = this.peer;
		if (p != null && p.isOpen()) {
			p.send(message);
		}
	}

	/** Pings ULTRAKILL; {@code answer} gets the round trip in ms on the client thread, or -1 after 2 s without a reply. */
	public void ping(final DoubleConsumer answer) {
		int id = this.pingIds.incrementAndGet();
		if (answer != null) {
			this.waiting.put(id, answer);
			Thread.ofVirtual().start(() -> {
				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					return;
				}

				DoubleConsumer late = this.waiting.remove(id);
				if (late != null) {
					Minecraft.getInstance().execute(() -> late.accept(-1.0));
				}
			});
		}

		JsonObject m = type("ping");
		m.addProperty("id", id);
		m.addProperty("ts", System.nanoTime());
		this.send(m);
	}

	/** Client thread, every tick: a ping once a second, and drop a peer that has gone quiet. */
	void tick() {
		long now = System.nanoTime();
		WebSocket p = this.peer;
		if (p == null) {
			return;
		}

		if (now - this.lastHeardNanos > TIMEOUT_NANOS && p.isOpen()) {
			UKPassthrough.LOG.warn("ULTRAKILL went quiet for 3 s: dropping the link");
			p.close(1001, "timeout");
			return;
		}

		if (now >= this.nextPingNanos) {
			this.nextPingNanos = now + PING_EVERY_NANOS;
			this.ping(null);
		}
	}

	@Override
	public void onStart() {
		UKPassthrough.LOG.info("ULTRAKILL link listening on 127.0.0.1:{}", this.getPort());
	}

	@Override
	public void onOpen(final WebSocket conn, final ClientHandshake handshake) {
		UKPassthrough.LOG.info("connection from {}", conn.getRemoteSocketAddress());
		JsonObject hello = type("hello");
		hello.addProperty("v", PROTOCOL);
		hello.addProperty("side", "minecraft");
		hello.addProperty("mod", UKPassthrough.VERSION);
		hello.addProperty("mc", net.minecraft.SharedConstants.getCurrentVersion().name());
		hello.addProperty("pid", ProcessHandle.current().pid());
		conn.send(hello.toString());
	}

	@Override
	public void onClose(final WebSocket conn, final int code, final String reason, final boolean remote) {
		if (conn == this.peer) {
			this.peer = null;
			this.rttMillis = -1.0;
			UKPassthrough.LOG.info("ULTRAKILL disconnected ({} {})", code, reason);
			chat("§6ULTRAKILL disconnected");
		}
	}

	@Override
	public void onMessage(final WebSocket conn, final String message) {
		try {
			JsonObject m = JsonParser.parseString(message).getAsJsonObject();
			if (conn == this.peer) {
				this.lastHeardNanos = System.nanoTime();
			}

			switch (m.get("t").getAsString()) {
				case "hello" -> this.hello(conn, m);
				case "ping" -> {
					JsonObject pong = type("pong");
					pong.add("id", m.get("id"));
					pong.add("ts", m.get("ts"));
					conn.send(pong.toString());
				}
				case "pong" -> {
					double rtt = (System.nanoTime() - m.get("ts").getAsLong()) / 1.0e6;
					this.rttMillis = rtt;
					DoubleConsumer answer = this.waiting.remove(m.get("id").getAsInt());
					if (answer != null) {
						Minecraft.getInstance().execute(() -> answer.accept(rtt));
					}
				}
				case "say" -> chat("§b[ULTRAKILL]§r " + m.get("text").getAsString());
				case "cam" -> V1State.updateCamera(m);
				case "lightat" -> V1Light.onLightAt(m);
				case "slam" -> BlockBreaks.onSlam(m);
				case "shatter" -> BlockBreaks.onShatter(m);
				case "hit" -> MobHits.onHit(m);
				case "parry" -> Projectiles.onParry(m);
				case "pull" -> MobHits.onPull(m);
				case "launch" -> MobHits.onLaunch(m);
				case "ukenemies" -> UkEnemies.onList(m);
				case "pointer" -> Control.onPointer(m);
				case "erase" -> ArmTools.onErase(m);
				case "carry" -> ArmTools.onCarry(m);
				case "debugmenu" -> DevDemo.onMenu(m);
				case "debugterminal" -> DevDemo.onTerminal(m);
				case "oil" -> Oil.onOil(m);
				case "fire" -> Fires.onFire(m);
				case "boom" -> Explosions.onBoom(m);
				case "totem" -> PlayerRules.onTotem(m);
				case "respawn" -> Respawn.onRespawn(m);
				case "died" -> Respawn.onDied(m);
				case "punch" -> Punches.onPunch(m);
				case "hammer" -> Punches.onHammer(m);
				case "use" -> Punches.onUse(m);
				case "hand" -> V1Hand.onHand(m);
				case "letgo" -> V1Hand.onLetGo(m);
				case "armout" -> V1Hand.onArmOut(m);
				case "ukprefs" -> ModSettings.onUkPrefs(m);
				case "speed" -> GameSpeed.onSpeed(m);
				case "binds" -> ControlsScreen.onBinds(m);
				case "loadoutset" -> ModSettings.onLoadoutSet(m);
				case "spend" -> Progression.onSpend(m);
				default -> UKPassthrough.LOG.debug("unhandled message {}", m.get("t"));
			}
		} catch (RuntimeException e) {
			UKPassthrough.LOG.warn("bad message {}: {}", message.length() > 200 ? message.substring(0, 200) : message, e.toString());
		}
	}

	private void hello(final WebSocket conn, final JsonObject m) {
		if (!m.has("side") || !"ultrakill".equals(m.get("side").getAsString())) {
			return;
		}

		int v = m.has("v") ? m.get("v").getAsInt() : 0;
		if (v != PROTOCOL) {
			UKPassthrough.LOG.warn("ULTRAKILL plugin speaks protocol {}, this mod speaks {}", v, PROTOCOL);
			chat("§cULTRAKILL plugin version doesn't match this mod (protocol " + v + " vs " + PROTOCOL + "). Install both files from the same release.");
		}

		WebSocket old = this.peer;
		this.peer = conn;
		this.generation++;
		this.lastHeardNanos = System.nanoTime();
		this.nextPingNanos = 0L;
		String plugin = m.has("plugin") ? m.get("plugin").getAsString() : "?";
		String game = m.has("game") ? m.get("game").getAsString() : "?";
		this.peerInfo = "plugin " + plugin + ", game " + game;
		if (old != null && old != conn && old.isOpen()) {
			old.close(1000, "replaced by a new ULTRAKILL connection");
		}

		UKPassthrough.LOG.info("ULTRAKILL connected: {}", this.peerInfo);
		chat("§aULTRAKILL connected§r (" + this.peerInfo + ")");
	}

	@Override
	public void onError(final WebSocket conn, final Exception e) {
		UKPassthrough.LOG.warn("link error", e);
	}

	static JsonObject type(final String t) {
		JsonObject m = new JsonObject();
		m.addProperty("t", t);
		return m;
	}

	/** A line in the chat, from any thread. */
	static void chat(final String text) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> minecraft.gui.hud.getChat().addClientSystemMessage(Component.literal(text)));
	}
}
