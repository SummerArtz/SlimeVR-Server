package dev.slimevr.serial;

import dev.slimevr.VRServer;
import io.eiren.util.logging.LogManager;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;


public class ProvisioningHandler implements SerialListener {

	private ProvisioningStatus provisioningStatus = ProvisioningStatus.NONE;

	private boolean isRunning = false;
	private final List<ProvisioningListener> listeners = new CopyOnWriteArrayList<>();

	private String ssid;
	private String password;

	private String preferredPort;

	private final Timer provisioningTickTimer = new Timer("ProvisioningTickTimer");
	private long lastStatusChange = -1;
	private byte connectRetries = 0;
	private final byte MAX_CONNECTION_RETRIES = 2;
	private final VRServer vrServer;

	public ProvisioningHandler(VRServer vrServer) {
		this.vrServer = vrServer;
		vrServer.serialHandler.addListener(this);
		this.provisioningTickTimer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				if (!isRunning)
					return;
				provisioningTick();
			}
		}, 0, 1000);
	}


	public void start(String ssid, String password, String port) {
		this.isRunning = true;
		this.ssid = ssid;
		this.password = password;
		this.preferredPort = port;
		this.provisioningStatus = ProvisioningStatus.NONE;
		this.connectRetries = 0;
	}

	public void stop() {
		this.isRunning = false;
		this.ssid = null;
		this.password = null;
		this.connectRetries = 0;
		this.changeStatus(ProvisioningStatus.NONE);
		this.vrServer.serialHandler.closeSerial();
	}

	public void initSerial(String port) {
		this.provisioningStatus = ProvisioningStatus.SERIAL_INIT;

		try {
			boolean openResult = false;
			if (port != null)
				openResult = vrServer.serialHandler.openSerial(port, false);
			else
				openResult = vrServer.serialHandler.openSerial(null, true);
			if (!openResult)
				LogManager.info("[SerialHandler] Serial port wasn't open...");
		} catch (Exception e) {
			LogManager.severe("[SerialHandler] Unable to open serial port", e);
		} catch (Throwable e) {
			LogManager
				.severe("[SerialHandler] Using serial ports is not supported on this platform", e);
		}

	}


	public void tryProvisioning() {
		this.changeStatus(ProvisioningStatus.PROVISIONING);
		vrServer.serialHandler.setWifi(this.ssid, this.password);
	}


	public void provisioningTick() {

		if (
			this.provisioningStatus == ProvisioningStatus.CONNECTION_ERROR
				|| this.provisioningStatus == ProvisioningStatus.DONE
		)
			return;


		if (System.currentTimeMillis() - this.lastStatusChange > 10000) {
			if (this.provisioningStatus == ProvisioningStatus.NONE)
				this.initSerial(this.preferredPort);
			else if (this.provisioningStatus == ProvisioningStatus.SERIAL_INIT)
				initSerial(this.preferredPort);
			else if (this.provisioningStatus == ProvisioningStatus.PROVISIONING)
				this.tryProvisioning();
			else if (this.provisioningStatus == ProvisioningStatus.LOOKING_FOR_SERVER)
				this.changeStatus(ProvisioningStatus.COULD_NOT_FIND_SERVER);
		}
	}


	@Override
	public void onSerialConnected(@NotNull SerialPort port) {
		if (!isRunning)
			return;
		this.tryProvisioning();
	}

	@Override
	public void onSerialDisconnected() {
		if (!isRunning)
			return;
		this.changeStatus(ProvisioningStatus.NONE);
		this.connectRetries = 0;
	}

	@Override
	public void onSerialLog(@NotNull String str) {
		if (!isRunning)
			return;

		if (
			provisioningStatus == ProvisioningStatus.PROVISIONING
				&& str.contains("New wifi credentials set")
		) {
			this.changeStatus(ProvisioningStatus.CONNECTING);
		}

		if (
			provisioningStatus == ProvisioningStatus.CONNECTING
				&& (str.contains("Looking for the server")
					|| str.contains("Searching for the server"))
		) {
			this.changeStatus(ProvisioningStatus.LOOKING_FOR_SERVER);
		}

		if (
			provisioningStatus == ProvisioningStatus.LOOKING_FOR_SERVER
				&& str.contains("Handshake successful")
		) {
			this.changeStatus(ProvisioningStatus.DONE);
		}

		if (
			provisioningStatus == ProvisioningStatus.CONNECTING
				&& str.contains("Can't connect from any credentials")
		) {
			if (++connectRetries >= MAX_CONNECTION_RETRIES) {
				this.changeStatus(ProvisioningStatus.CONNECTION_ERROR);
			} else {
				this.vrServer.serialHandler.rebootRequest();
			}
		}
	}

	public void changeStatus(ProvisioningStatus status) {
		this.lastStatusChange = System.currentTimeMillis();
		if (this.provisioningStatus != status) {
			this.listeners.forEach((l) -> l.onProvisioningStatusChange(status));
			this.provisioningStatus = status;
		}
	}

	@Override
	public void onNewSerialDevice(SerialPort port) {
		if (!isRunning)
			return;
		this.initSerial(this.preferredPort);
	}

	public void addListener(ProvisioningListener channel) {
		this.listeners.add(channel);
	}

	public void removeListener(ProvisioningListener l) {
		listeners.removeIf(listener -> l == listener);
	}

}
