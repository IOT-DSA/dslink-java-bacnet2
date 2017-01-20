package bacnet;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.serotonin.bacnet4j.transport.DefaultTransport;
import com.serotonin.io.serial.SerialPortException;
import com.serotonin.io.serial.SerialPortProxy;
import com.serotonin.io.serial.SerialUtils;

import bacnet.properties.LocalBacnetProperty;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.Objects;
import org.dsa.iot.dslink.util.StringUtils;
import org.dsa.iot.dslink.util.handler.Handler;
import org.dsa.iot.dslink.util.json.JsonArray;
import org.dsa.iot.dslink.util.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.LocalDevice;
import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.RemoteObject;
import com.serotonin.bacnet4j.base.BACnetUtils;
import com.serotonin.bacnet4j.event.DeviceEventAdapter;
import com.serotonin.bacnet4j.event.DeviceEventListener;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.npdu.Network;
import com.serotonin.bacnet4j.npdu.ip.IpNetwork;
import com.serotonin.bacnet4j.npdu.ip.IpNetworkUtils;
import com.serotonin.bacnet4j.npdu.mstp.MasterNode;
import com.serotonin.bacnet4j.npdu.mstp.MstpNetwork;
import com.serotonin.bacnet4j.obj.BACnetObject;
import com.serotonin.bacnet4j.service.confirmed.ReinitializeDeviceRequest.ReinitializedStateOfDevice;
import com.serotonin.bacnet4j.service.unconfirmed.WhoIsRequest;
import com.serotonin.bacnet4j.transport.Transport;
import com.serotonin.bacnet4j.type.constructed.Address;
import com.serotonin.bacnet4j.type.constructed.BACnetError;
import com.serotonin.bacnet4j.type.constructed.Choice;
import com.serotonin.bacnet4j.type.constructed.DateTime;
import com.serotonin.bacnet4j.type.constructed.PropertyValue;
import com.serotonin.bacnet4j.type.constructed.Sequence;
import com.serotonin.bacnet4j.type.constructed.SequenceOf;
import com.serotonin.bacnet4j.type.constructed.TimeStamp;
import com.serotonin.bacnet4j.type.enumerated.EventState;
import com.serotonin.bacnet4j.type.enumerated.EventType;
import com.serotonin.bacnet4j.type.enumerated.MessagePriority;
import com.serotonin.bacnet4j.type.enumerated.NotifyType;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.notificationParameters.NotificationParameters;
import com.serotonin.bacnet4j.type.primitive.Boolean;
import com.serotonin.bacnet4j.type.primitive.CharacterString;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.OctetString;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import com.serotonin.bacnet4j.util.RequestListener;
import com.serotonin.bacnet4j.util.RequestUtils;
import com.serotonin.bacnet4j.util.sero.SerialPortWrapper;
import com.serotonin.io.serial.SerialParameters;

abstract class BacnetConn {
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(BacnetConn.class);
	}

	static final String ACTION_ADD_LOCAL_SLAVE = "set up ip slave";
	static final String ACTION_REMOVE = "remove";
	static final String ACTION_EDIT = "edit";
	static final String ACTION_STOP = "stop";
	static final String ACTION_RESTART = "restart";
	static final String ACTION_DISCOVER_DEVICES = "discover devices";
	static final String ACTION_ADD_DEVICE = "add device";

	static final String ATTRIBUTE_NAME = "name";
	static final String ATTRIBUTE_MAC_ADDRESS = "MAC address";
	static final String ATTRIBUTE_INSTANCE_NUMBER = "instance number";
	static final String ATTRIBUTE_NETWOR_NUMBER = "network number";
	static final String ATTRIBUTE_LINK_SERVICE_MAC = "link service MAC";
	static final String ATTRIBUTE_POLLING_INTERVAL = "polling interval";
	static final String ATTRIBUTE_COV_USAGE = "cov usage";
	static final String ATTRIBUTE_COV_LEASE_TIME = "cov lease time (minutes)";

	static final String ATTRIBUTE_RESTORE_TYPE = "restore type";
	static final String RESTORE_EDITABLE_FOLDER = "editable folder";

	static final String NODE_STATUS = "STATUS";
	static final String NODE_STATUS_SETTING_UP_CONNECTION = "Setting up connection";
	static final String NODE_STATUS_CONNECTED = "Connected";
	static final String NODE_STATUS_STOPPED = "Stopped";

	Node node;
	final Node statnode;
	LocalDevice localDevice;
	Transport transport;

	int localNetworkNumber;
	int timeout;
	int segmentTimeout;
	int segmentWindow;
	int retries;
	int localDeviceId;
	String localDeviceName;
	String localDeviceVendor;
	long defaultInterval;
	BacnetLink link;

	final Set<DeviceNode> deviceNodes = new HashSet<DeviceNode>();
	LocalDeviceFolder localDeviceNode;
	Map<BACnetObject, EditablePoint> ObjectToPoint = new HashMap<BACnetObject, EditablePoint>();
	final Map<Integer, OctetString> networkRouters = new HashMap<Integer, OctetString>();
	final Map<String, Integer> bbmdIpToPort = new HashMap<String, Integer>();
	private ScheduledFuture<?> reconnectFuture = null;
	private int retryDelay = 1;

	ScheduledThreadPoolExecutor serialStpe;
	DeviceEventListener listener;

	BacnetConn(BacnetLink link, Node node) {
		this.node = node;
		this.link = link;

		defaultInterval = node.getAttribute("default polling interval").getNumber().longValue();
		localNetworkNumber = node.getAttribute("local network number").getNumber().intValue();
		timeout = node.getAttribute("Timeout").getNumber().intValue();
		segmentTimeout = node.getAttribute("segment timeout").getNumber().intValue();
		segmentWindow = node.getAttribute("segment window").getNumber().intValue();
		retries = node.getAttribute("retries").getNumber().intValue();
		localDeviceId = node.getAttribute("local device id").getNumber().intValue();
		localDeviceName = node.getAttribute("local device name").getString();
		localDeviceVendor = node.getAttribute("local device vendor").getString();
		defaultInterval = node.getAttribute("default polling interval").getNumber().longValue();

		initializeScheduledThreadPoolExecutor();

		this.statnode = node.createChild(NODE_STATUS).setValueType(ValueType.STRING).setValue(new Value("")).build();
		this.statnode.setSerializable(false);

		this.listener = new EventListenerImpl();
	}

	void initializeScheduledThreadPoolExecutor() {
		serialStpe = null;
	}

	ScheduledThreadPoolExecutor getDaemonThreadPool() {
		return serialStpe;
	}

	void init() {
		if (reconnectFuture != null) {
			reconnectFuture.cancel(false);
			reconnectFuture = null;
		}
		statnode.setValue(new Value(NODE_STATUS_SETTING_UP_CONNECTION));

		Action act = new Action(Permission.READ, new RemoveHandler());
		Node anode = node.getChild(ACTION_REMOVE);
		if (anode == null)
			node.createChild(ACTION_REMOVE).setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);

		act = getEditAction();
		anode = node.getChild(ACTION_EDIT);
		if (anode == null) {
			anode = node.createChild(ACTION_EDIT).setAction(act).build();
			anode.setSerializable(false);
		} else {
			anode.setAction(act);
		}

		Network network;
		network = createNetwork(localNetworkNumber);
		if (network != null) {
			transport = new DefaultTransport(network);
			transport.setTimeout(timeout);
			transport.setSegTimeout(segmentTimeout);
			transport.setSegWindow(segmentWindow);
			transport.setRetries(retries);
			if (!networkRouters.isEmpty()) {
				for (Map.Entry<Integer, OctetString> entry : networkRouters.entrySet()) {
					Integer networkNumber = entry.getKey();
					OctetString linkService = entry.getValue();
					transport.addNetworkRouter(networkNumber, linkService);
				}
			}
			localDevice = new LocalDevice(localDeviceId, transport);
			try {
				localDevice.getConfiguration().writeProperty(PropertyIdentifier.objectName,
						new CharacterString(localDeviceName));
				localDevice.getConfiguration().writeProperty(PropertyIdentifier.vendorName,
						new CharacterString(localDeviceVendor));
			} catch (Exception e1) {
				LOGGER.debug("error: ", e1);
			}
			// localDevice.setStrict(strict);
			try {
				localDevice.initialize();
				registerAsFeignDevice(network);
				localDevice.getEventHandler().addListener(this.listener);
				localDevice.sendGlobalBroadcast(localDevice.getIAm());
				// Thread.sleep(200000);
			} catch (Exception e) {
				// e.printStackTrace();
				// remove();
				LOGGER.debug("error: ", e);
				statnode.setValue(new Value("Error in initializing local device :" + e.getMessage()));
				localDevice.terminate();
				localDevice = null;
			} finally {
				// localDevice.terminate();
			}
		} else {
			localDevice = null;
		}

		if (!NODE_STATUS_STOPPED.equals(statnode.getValue().getString())) {
			act = new Action(Permission.READ, new StopHandler());
			anode = node.getChild(ACTION_STOP);
			if (anode == null)
				node.createChild(ACTION_STOP).setAction(act).build().setSerializable(false);
			else
				anode.setAction(act);
		}

		act = new Action(Permission.READ, new RestartHandler());
		anode = node.getChild(ACTION_RESTART);
		if (anode == null)
			node.createChild(ACTION_RESTART).setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);

		if (localDevice != null) {
			retryDelay = 1;
			act = new Action(Permission.READ, new DeviceDiscoveryHandler());
			anode = node.getChild(ACTION_DISCOVER_DEVICES);
			if (anode == null)
				node.createChild(ACTION_DISCOVER_DEVICES).setAction(act).build().setSerializable(false);
			else
				anode.setAction(act);

			act = getMakeSlaveAction();
			node.createChild(ACTION_ADD_LOCAL_SLAVE).setAction(act).build().setSerializable(false);

			act = new Action(Permission.READ, new AddDeviceHandler());
			act.addParameter(new Parameter("name", ValueType.STRING));

			String defMac = getDefaultMac();

			act.addParameter(new Parameter(ATTRIBUTE_MAC_ADDRESS, ValueType.STRING, new Value(defMac)));
			act.addParameter(new Parameter(ATTRIBUTE_INSTANCE_NUMBER, ValueType.NUMBER));
			act.addParameter(new Parameter(ATTRIBUTE_NETWOR_NUMBER, ValueType.NUMBER, new Value(0)));
			act.addParameter(new Parameter(ATTRIBUTE_LINK_SERVICE_MAC, ValueType.STRING));
			act.addParameter(new Parameter(ATTRIBUTE_POLLING_INTERVAL, ValueType.NUMBER,
					new Value(((double) defaultInterval) / 1000)));
			act.addParameter(
					new Parameter(ATTRIBUTE_COV_USAGE, ValueType.makeEnum("NONE", "UNCONFIRMED", "CONFIRMED")));
			act.addParameter(new Parameter(ATTRIBUTE_COV_LEASE_TIME, ValueType.NUMBER, new Value(60)));
			anode = node.getChild(ACTION_ADD_DEVICE);
			if (anode == null)
				node.createChild(ACTION_ADD_DEVICE).setAction(act).build().setSerializable(false);
			else
				anode.setAction(act);
			statnode.setValue(new Value(NODE_STATUS_CONNECTED));

		} else if (!NODE_STATUS_STOPPED.equals(statnode.getValue().getString())) {
			ScheduledThreadPoolExecutor reconnectStpe = Objects.getDaemonThreadPool();
			reconnectFuture = reconnectStpe.schedule(new Runnable() {

				@Override
				public void run() {
					Value stat = statnode.getValue();
					if (stat == null || !(NODE_STATUS_CONNECTED.equals(stat.getString())
							|| NODE_STATUS_SETTING_UP_CONNECTION.equals(stat.getString()))) {
						restoreLastSession();
					}
				}

			}, retryDelay, TimeUnit.SECONDS);
			if (retryDelay < 60)
				retryDelay += 2;
		}
	}

	String getDefaultMac() {
		return "10";
	}

	abstract Network createNetwork(int localNetworkNumber);

	abstract void addTransportParameters(Action act);

	abstract void setTransportAtrributions(ActionResult event);

	abstract BacnetConn getBacnetConnection(BacnetLink link, Node newnode);

	void registerAsFeignDevice(Network network) {

	}

	static class SerialPortWrapperImpl extends SerialPortWrapper {

		private SerialParameters params;
		private SerialPortProxy spp = null;

		SerialPortWrapperImpl(String commPort, int baud, String owner) throws SerialPortException {
			params = new SerialParameters();
			params.setCommPortId(commPort);
			params.setBaudRate(baud);
			params.setPortOwnerName(owner);
		}

		@Override
		public void close() throws Exception {
			if (spp != null)
				SerialUtils.close(spp);
			spp = null;

		}

		@Override
		public String getCommPortId() {
			return params.getCommPortId();
		}

		@Override
		public InputStream getInputStream() {
			return (spp != null) ? spp.getInputStream() : null;
		}

		@Override
		public OutputStream getOutputStream() {
			return (spp != null) ? spp.getOutputStream() : null;
		}

		@Override
		public void open() throws Exception {
			spp = SerialUtils.openSerialPort(params);

		}

		@Override
		public int getFlowControlIn() {
			return params.getFlowControlIn();
		}

		@Override
		public int getFlowControlOut() {
			return params.getFlowControlOut();
		}

		@Override
		public int getDataBits() {
			return params.getDataBits();
		}

		@Override
		public int getStopBits() {
			return params.getStopBits();
		}

		@Override
		public int getParity() {
			return params.getParity();
		}
	}

	private Action getMakeSlaveAction() {
		Action act = new Action(Permission.READ, new MakeSlaveHandler());
		act.addParameter(new Parameter(ATTRIBUTE_NAME, ValueType.STRING));

		return act;
	}

	Action getEditAction() {
		Action act = new Action(Permission.READ, new EditHandler());
		act.addParameter(new Parameter("name", ValueType.STRING, new Value(node.getName())));

		addTransportParameters(act);

		// common parameters
		act.addParameter(
				new Parameter("local network number", ValueType.NUMBER, node.getAttribute("local network number")));
		act.addParameter(new Parameter("strict device comparisons", ValueType.BOOL,
				node.getAttribute("strict device comparisons")));
		act.addParameter(new Parameter("Timeout", ValueType.NUMBER, node.getAttribute("Timeout")));
		act.addParameter(new Parameter("segment timeout", ValueType.NUMBER, node.getAttribute("segment timeout")));
		act.addParameter(new Parameter("segment window", ValueType.NUMBER, node.getAttribute("segment window")));
		act.addParameter(new Parameter("retries", ValueType.NUMBER, node.getAttribute("retries")));
		act.addParameter(new Parameter("local device id", ValueType.NUMBER, node.getAttribute("local device id")));
		act.addParameter(new Parameter("local device name", ValueType.STRING, node.getAttribute("local device name")));
		act.addParameter(
				new Parameter("local device vendor", ValueType.STRING, node.getAttribute("local device vendor")));
		double defint = node.getAttribute("default polling interval").getNumber().doubleValue() / 1000;
		act.addParameter(new Parameter("default polling interval", ValueType.NUMBER, new Value(defint)));
		return act;
	}

	private class StopHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			if (reconnectFuture != null) {
				reconnectFuture.cancel(false);
				reconnectFuture = null;
			}
			stop();
		}
	}

	private class RestartHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			retryDelay = 1;
			stop();
			restoreLastSession();
		}
	}

	void stop() {
		if (localDevice != null) {
			localDevice.terminate();
			localDevice = null;
			node.removeChild(ACTION_STOP);
			node.removeChild(ACTION_DISCOVER_DEVICES);
			node.removeChild(ACTION_ADD_DEVICE);
			statnode.setValue(new Value(NODE_STATUS_STOPPED));
		}
	}

	private class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();

			setTransportAtrributions(event);

			// common attribution
			int localNetworkNumber = event.getParameter("local network number", ValueType.NUMBER).getNumber()
					.intValue();
			boolean strict = event.getParameter("strict device comparisons", ValueType.BOOL).getBool();
			int timeout = event.getParameter("Timeout", ValueType.NUMBER).getNumber().intValue();
			int segtimeout = event.getParameter("segment timeout", ValueType.NUMBER).getNumber().intValue();
			int segmentWindow = event.getParameter("segment window", ValueType.NUMBER).getNumber().intValue();
			int retries = event.getParameter("retries", ValueType.NUMBER).getNumber().intValue();
			int localDeviceId = event.getParameter("local device id", ValueType.NUMBER).getNumber().intValue();
			String localDeviceName = event.getParameter("local device name", ValueType.STRING).getString();
			String localDeviceVendor = event.getParameter("local device vendor", ValueType.STRING).getString();
			long intervalInMilliseconds = (long) (1000
					* event.getParameter("default polling interval", ValueType.NUMBER).getNumber().doubleValue());

			node.setAttribute("local network number", new Value(localNetworkNumber));
			node.setAttribute("strict device comparisons", new Value(strict));
			node.setAttribute("Timeout", new Value(timeout));
			node.setAttribute("segment timeout", new Value(segtimeout));
			node.setAttribute("segment window", new Value(segmentWindow));
			node.setAttribute("retries", new Value(retries));
			node.setAttribute("local device id", new Value(localDeviceId));
			node.setAttribute("local device name", new Value(localDeviceName));
			node.setAttribute("local device vendor", new Value(localDeviceVendor));
			node.setAttribute("default polling interval", new Value(intervalInMilliseconds));

			stop();

			if (!name.equals(node.getName())) {
				rename(name);
			}

			restoreLastSession();
		}
	}

	private class RemoveHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			remove();
		}
	}

	protected class CopyHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String newname = event.getParameter("name", ValueType.STRING).getString();
			if (newname.length() > 0 && !newname.equals(node.getName()))
				duplicate(newname);
		}
	}

	private void remove() {
		stop();
		node.clearChildren();
		link.serialConns.remove(getMe());
		node.getParent().removeChild(node);

		shutdown();
	}

	void shutdown() {

	}

	protected void rename(String name) {
		duplicate(name);
		remove();
	}

	protected void duplicate(String name) {
		JsonObject jobj = link.copySerializer.serialize();
		JsonObject nodeobj = jobj.get(node.getName());
		jobj.put(name, nodeobj);
		link.copyDeserializer.deserialize(jobj);
		Node newnode = node.getParent().getChild(name);
		BacnetConn bc = getBacnetConnection(link, newnode);
		bc.restoreLastSession();
	}

	private class AddDeviceHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = null;
			Value namev = event.getParameter(ATTRIBUTE_NAME, ValueType.STRING);
			if (namev != null)
				name = namev.getString();
			long interval = (long) (1000
					* event.getParameter(ATTRIBUTE_POLLING_INTERVAL, ValueType.NUMBER).getNumber().doubleValue());
			CovType covtype = CovType.NONE;
			try {
				covtype = CovType.valueOf(event.getParameter(ATTRIBUTE_COV_USAGE).getString());
			} catch (Exception e1) {
			}
			int covlife = event.getParameter(ATTRIBUTE_COV_LEASE_TIME, ValueType.NUMBER).getNumber().intValue();
			String mac = event.getParameter(ATTRIBUTE_MAC_ADDRESS, new Value("")).getString();
			int instanceNum = event.getParameter(ATTRIBUTE_INSTANCE_NUMBER, new Value(-1)).getNumber().intValue();
			int netNum = event.getParameter(ATTRIBUTE_NETWOR_NUMBER, ValueType.NUMBER).getNumber().intValue();
			String linkMac = event.getParameter(ATTRIBUTE_LINK_SERVICE_MAC, new Value("")).getString();

			RemoteDevice dev = getDevice(mac, instanceNum, netNum, linkMac, interval, covtype, covlife);

			setupDeviceNode(dev, null, name, mac, instanceNum, netNum, linkMac, interval, covtype, covlife);
		}
	}

	RemoteDevice getDevice(String mac, int instanceNum, int netNum, String linkMac, long interval, CovType covtype,
			int covlife) {
		if (localDevice == null) {
			stop();
			return null;
		}

		ConcurrentLinkedQueue<RemoteDevice> devs = new ConcurrentLinkedQueue<RemoteDevice>();
		DiscoveryListener dl = new DiscoveryListener(devs);
		if (!mac.isEmpty() && instanceNum >= 0) {
			Address address = Utils.toAddress(netNum, mac);
			try {
				return localDevice.findRemoteDevice(address, instanceNum);
			} catch (BACnetException e) {
				LOGGER.debug("", e);
			}
		}
		localDevice.getEventHandler().addListener(dl);
		try {
			if (mac.isEmpty())
				localDevice.sendGlobalBroadcast(
						new WhoIsRequest(new UnsignedInteger(instanceNum), new UnsignedInteger(instanceNum)));
			else {
				Address addr = Utils.toAddress(netNum, mac);
				if (instanceNum < 0) {
					localDevice.send(addr, new WhoIsRequest());
				} else {
					localDevice.send(addr,
							new WhoIsRequest(new UnsignedInteger(instanceNum), new UnsignedInteger(instanceNum)));
				}
			}
		} catch (Exception e1) {
			LOGGER.debug("error: ", e1);
		} finally {
			int totaltime = 0;
			int waittime = 500;
			while (devs.size() < 1 && totaltime < 10000) {
				try {
					totaltime += waittime;
					Thread.sleep(waittime);
				} catch (InterruptedException e) {
					LOGGER.debug("error: ", e);
				}
			}
			localDevice.getEventHandler().removeListener(dl);
		}
		return devs.poll();
	}

	private class MakeSlaveHandler implements Handler<ActionResult> {

		public void handle(ActionResult event) {

			String name = event.getParameter(ATTRIBUTE_NAME, ValueType.STRING).getString();
			Node slaveNode;
			slaveNode = node.createChild(name).build();

			localDeviceNode = new LocalDeviceNode(getMe(), slaveNode, localDevice);
		}
	}

	private class DeviceDiscoveryHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			if (localDevice == null) {
				stop();
				return;
			}

			ConcurrentLinkedQueue<RemoteDevice> devs = new ConcurrentLinkedQueue<RemoteDevice>();
			DiscoveryListener dl = new DiscoveryListener(devs);
			localDevice.getEventHandler().addListener(dl);
			try {
				localDevice.sendGlobalBroadcast(new WhoIsRequest());
				int totaltime = 0;
				int waittime = 500;
				while (totaltime <= 15000 || devs.size() > 0) {
					Thread.sleep(waittime);
					totaltime += waittime;
					RemoteDevice d = devs.poll();
					if (d != null && !devInTree(d)) {
						// map network to link service for bacnet routers
						OctetString deviceMacAddress = d.getAddress().getMacAddress();
						int deviceInstanceNumber = d.getInstanceNumber();
						int deviceNetworkNumber = d.getAddress().getNetworkNumber().intValue();
						if (deviceNetworkNumber == 0) {
							transport.addNetworkRouter(deviceInstanceNumber, deviceMacAddress);
							networkRouters.put(deviceInstanceNumber, deviceMacAddress);
						}
						setupDeviceNode(d, null, null, null, null, null, null, defaultInterval, CovType.NONE, 60);
					}
				}
			} catch (Exception e) {
				LOGGER.error("error: ", e);
			} finally {
				localDevice.getEventHandler().removeListener(dl);
			}
		}
	}

	private boolean devInTree(RemoteDevice d) {
		if (node.getChildren() == null)
			return false;
		String deviceMacAddress = Utils.getMac(d);
		int deviceInstanceNumber = d.getInstanceNumber();
		int deviceNetworkNumber = d.getAddress().getNetworkNumber().intValue();
		for (Node child : node.getChildren().values()) {
			Value macAddress = child.getAttribute(ATTRIBUTE_MAC_ADDRESS);
			Value instanceNumber = child.getAttribute(ATTRIBUTE_INSTANCE_NUMBER);
			Value networkNumber = child.getAttribute(ATTRIBUTE_NETWOR_NUMBER);
			if ((macAddress != null && deviceMacAddress.equals(macAddress.getString()))
					&& (instanceNumber != null && instanceNumber.getNumber().intValue() == deviceInstanceNumber)
					&& (networkNumber != null && networkNumber.getNumber().intValue() == deviceNetworkNumber)) {
				return true;
			}
		}
		return false;
	}

	void getDeviceProps(final RemoteDevice d) {
		LocalDevice ld = localDevice;
		if (d == null || ld == null)
			return;

		try {
			RequestUtils.getProperties(ld, d, new RequestListener() {
				public boolean requestProgress(double progress, ObjectIdentifier oid, PropertyIdentifier pid,
						UnsignedInteger pin, Encodable value) {
					if (pid.equals(PropertyIdentifier.objectName)) {
						String name = toLegalName(value.toString());
						if (value instanceof BACnetError || name.length() < 1) {
							int instanceNumber = d.getInstanceNumber();
							String macAddress = Utils.getMac(d);
							d.setName("Device " + instanceNumber + " - " + macAddress);
						} else {
							d.setName(name);
						}
					}
					// else if (pid.equals(PropertyIdentifier.vendorName))
					// d.setVendorName(value.toString());
					// else if (pid.equals(PropertyIdentifier.modelName))
					// d.setModelName(value.toString());
					return false;
				}
			}, PropertyIdentifier.objectName);
		} catch (Exception e) {
			// e.printStackTrace();
			LOGGER.debug("error: ", e);
		}
		LOGGER.debug("Got device name: " + d.getName());
	}

	static String toLegalName(String s) {
		if (s == null)
			return "";
		return StringUtils.encodeName(s);
	}

	private DeviceNode setupDeviceNode(final RemoteDevice d, Node child, String name, String mac, Integer instanceNum,
			Integer netNum, String linkMac, long interval, CovType covtype, int covlife) {
		if (d != null)
			getDeviceProps(d);
		if (name == null && d != null)
			name = d.getName();
		if (linkMac == null)
			linkMac = "";
		if (mac == null)
			mac = "";
		if (name != null) {
			if (child == null) {
				String modname = name;
				child = node.getChild(modname);
				int i = 1;
				while (child != null) {
					i++;
					modname = name + i;
					child = node.getChild(modname);
				}
				child = node.createChild(modname).build();
			}
			if (d != null) {
				mac = Utils.getMac(d);
				linkMac = "";
				instanceNum = d.getInstanceNumber();
				netNum = d.getAddress().getNetworkNumber().intValue();
			}

			child.setAttribute(ATTRIBUTE_MAC_ADDRESS, new Value(mac));
			child.setAttribute(ATTRIBUTE_INSTANCE_NUMBER, new Value(instanceNum));
			child.setAttribute(ATTRIBUTE_NETWOR_NUMBER, new Value(netNum));
			child.setAttribute(ATTRIBUTE_LINK_SERVICE_MAC, new Value(linkMac));
			child.setAttribute(ATTRIBUTE_POLLING_INTERVAL, new Value(interval));
			child.setAttribute(ATTRIBUTE_COV_USAGE, new Value(covtype.toString()));
			child.setAttribute(ATTRIBUTE_COV_LEASE_TIME, new Value(covlife));
			return new DeviceNode(getMe(), child, d);
		}
		return null;
	}

	private static class DiscoveryListener extends DeviceEventAdapter {

		private Queue<RemoteDevice> devices;

		DiscoveryListener(Queue<RemoteDevice> devs) {
			devices = devs;
		}

		@Override
		public void iAmReceived(RemoteDevice d) {
			LOGGER.info("IAm received from " + d);
			devices.add(d);
		}
	}

	private BacnetConn getMe() {
		return this;
	}

	public void restoreLastSession() {
		init();

		if (null == localDevice || node.getChildren() == null)
			return;

		for (Node child : node.getChildren().values()) {
			restoreDevice(child);
		}
	}

	void restoreDevice(final Node child) {
		if (localDevice != null) {
			for (DeviceNode dn : deviceNodes) {
				if (child == dn.node && !dn.enabled) {
					dn.enable(true);
					return;
				}
			}
		}

		final Value mac = child.getAttribute(ATTRIBUTE_MAC_ADDRESS);
		final Value instanceNum = child.getAttribute(ATTRIBUTE_INSTANCE_NUMBER);
		final Value netNum = child.getAttribute(ATTRIBUTE_NETWOR_NUMBER);
		final Value linkMac = child.getAttribute(ATTRIBUTE_LINK_SERVICE_MAC);
		final Value refint = child.getAttribute(ATTRIBUTE_POLLING_INTERVAL);
		Value covtype = child.getAttribute(ATTRIBUTE_COV_USAGE);
		final Value covlife = child.getAttribute(ATTRIBUTE_COV_LEASE_TIME);
		Value restType = child.getAttribute(ATTRIBUTE_RESTORE_TYPE);
		if (mac != null && instanceNum != null && netNum != null && linkMac != null && refint != null && covtype != null
				&& covlife != null) {
			CovType ctype = CovType.NONE;
			try {
				ctype = CovType.valueOf(covtype.getString());
			} catch (Exception e) {
			}
			final CovType ct = ctype;

			boolean disabled = child.getChild("STATUS") != null
					&& (new Value("disabled").equals(child.getChild("STATUS").getValue())
							|| new Value("not connected").equals(child.getChild("STATUS").getValue()));
			DeviceNode dn = null;
			if (!disabled) {
				ScheduledThreadPoolExecutor gstpe = Objects.getDaemonThreadPool();
				gstpe.schedule(new Runnable() {

					@Override
					public void run() {
						RemoteDevice dev = getDevice(mac.getString(), instanceNum.getNumber().intValue(),
								netNum.getNumber().intValue(), linkMac.getString(), refint.getNumber().longValue(), ct,
								covlife.getNumber().intValue());
						DeviceNode dn = setupDeviceNode(dev, child, child.getName(), mac.getString(),
								instanceNum.getNumber().intValue(), netNum.getNumber().intValue(), linkMac.getString(),
								refint.getNumber().longValue(), ct, covlife.getNumber().intValue());
						if (dn != null)
							dn.restoreLastSession();
						else {
							node.removeChild(child);
						}

					}

				}, 0, TimeUnit.SECONDS);

			} else {
				dn = setupDeviceNode(null, child, child.getName(), mac.getString(), instanceNum.getNumber().intValue(),
						netNum.getNumber().intValue(), linkMac.getString(), refint.getNumber().longValue(), ct,
						covlife.getNumber().intValue());
				if (dn != null)
					dn.restoreLastSession();
				else {
					node.removeChild(child);
				}
			}

		} else if (restType != null && restType.getString().equals(RESTORE_EDITABLE_FOLDER)) {
			localDeviceNode = new LocalDeviceNode(getMe(), child, localDevice);
			localDeviceNode.restoreLastSession();
		} else if (child.getAction() == null && !child.getName().equals("STATUS")) {
			node.removeChild(child);
		}
	}

	private class EventListenerImpl implements DeviceEventListener {

		@Override
		public boolean allowPropertyWrite(Address arg0, BACnetObject arg1, PropertyValue arg2) {
			// May be configurable
			return true;
		}

		@Override
		public void covNotificationReceived(UnsignedInteger arg0, RemoteDevice arg1, ObjectIdentifier arg2,
				UnsignedInteger arg3, SequenceOf<PropertyValue> arg4) {
			// TODO Auto-generated method stub

		}

		@Override
		public void eventNotificationReceived(UnsignedInteger processIdentifier, RemoteDevice initiatingDevice,
				ObjectIdentifier eventObjectIdentifier, TimeStamp timeStamp, UnsignedInteger notificationClass,
				UnsignedInteger priority, EventType eventType, CharacterString messageText, NotifyType notifyType,
				Boolean ackRequired, EventState fromState, EventState toState, NotificationParameters eventValues) {

			JsonObject jo = new JsonObject();
			jo.put("Process Identifier", processIdentifier.intValue());
			jo.put("Object", eventObjectIdentifier.toString());
			jo.put("Timestamp", Utils.timestampToString(timeStamp));
			jo.put("Notification Class", notificationClass.intValue());
			jo.put("Notify Type", notifyType.toString());
			jo.put("Event Type", eventType.toString());
			jo.put("From Event State", fromState.toString());
			jo.put("To Event State", toState.toString());
			jo.put("Priority", priority.intValue());
			jo.put("Ack Required", ackRequired.booleanValue());
			jo.put("Message Text", messageText.toString());

			for (DeviceNode dn : deviceNodes) {
				if (initiatingDevice.equals(dn.device)) {
					JsonArray val = dn.eventnode.getValue().getArray();
					val.add(jo);
					dn.eventnode.setValue(new Value(val));
				}
			}
		}

		@Override
		public void iAmReceived(RemoteDevice arg0) {
			// TODO Auto-generated method stub

		}

		@Override
		public void iHaveReceived(RemoteDevice arg0, RemoteObject arg1) {
			// TODO Auto-generated method stub

		}

		@Override
		public void listenerException(Throwable arg0) {
			// TODO Auto-generated method stub

		}

		@Override
		public void privateTransferReceived(Address arg0, UnsignedInteger arg1, UnsignedInteger arg2, Sequence arg3) {
			// TODO Auto-generated method stub

		}

		@Override
		public void propertyWritten(Address adress, BACnetObject bacnetObj, PropertyValue propVal) {
			EditablePoint objectPoint = null;
			Encodable enc = propVal.getValue();

			objectPoint = ObjectToPoint.get(bacnetObj);
			objectPoint.updatePointValue(enc);

			PropertyIdentifier pid = propVal.getPropertyIdentifier();
			LocalBacnetProperty property = objectPoint.getProperty(pid);
			property.updatePropertyValue(enc);

		}

		@Override
		public void reinitializeDevice(Address arg0, ReinitializedStateOfDevice arg1) {
			// TODO Auto-generated method stub

		}

		@Override
		public void synchronizeTime(Address arg0, DateTime arg1, boolean arg2) {
			// TODO Auto-generated method stub

		}

		@Override
		public void textMessageReceived(RemoteDevice arg0, Choice arg1, MessagePriority arg2, CharacterString arg3) {
			// TODO Auto-generated method stub

		}

	}

	public LocalDevice getLocalDevice() {
		return this.localDevice;
	}

	public Map<BACnetObject, EditablePoint> getObjectToPoint() {
		return ObjectToPoint;
	}
}
