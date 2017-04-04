package bacnet;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.Objects;
import org.dsa.iot.dslink.util.handler.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.LocalDevice;
import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.RemoteObject;
import com.serotonin.bacnet4j.event.DeviceEventListener;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.npdu.Network;
import com.serotonin.bacnet4j.npdu.ip.IpNetwork;
import com.serotonin.bacnet4j.obj.BACnetObject;
import com.serotonin.bacnet4j.service.confirmed.ReinitializeDeviceRequest.ReinitializedStateOfDevice;
import com.serotonin.bacnet4j.service.unconfirmed.WhoIsRequest;
import com.serotonin.bacnet4j.transport.DefaultTransport;
import com.serotonin.bacnet4j.transport.Transport;
import com.serotonin.bacnet4j.type.constructed.Address;
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
import com.serotonin.bacnet4j.type.notificationParameters.NotificationParameters;
import com.serotonin.bacnet4j.type.primitive.Boolean;
import com.serotonin.bacnet4j.type.primitive.CharacterString;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.OctetString;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;

public abstract class BacnetConn implements DeviceEventListener {
	private static final Logger LOGGER = LoggerFactory.getLogger(BacnetConn.class);
	
	static final String ACTION_ADD_LOCAL_SLAVE = "set up ip slave";
	static final String ACTION_REMOVE = "remove";
	static final String ACTION_EDIT = "edit";
	static final String ACTION_STOP = "stop";
	static final String ACTION_RESTART = "restart";
	static final String ACTION_DISCOVER_DEVICES = "discover devices";
	static final String ACTION_ADD_DEVICE = "add device";
	static final String ACTION_ADD_CUSTOM_DEVICE = "add custom device";
	
	static final String NODE_STATUS = "STATUS";
	static final String NODE_STATUS_SETTING_UP_CONNECTION = "Setting up connection";
	static final String NODE_STATUS_CONNECTED = "Connected";
	static final String NODE_STATUS_STOPPED = "Stopped";
	
	protected Node node;
	protected BacnetLink link;
	private final Node statnode;
	private final ScheduledThreadPoolExecutor stpe = Objects.createDaemonThreadPool();
	
	private final Map<Integer, RemoteDevice> discovered = new ConcurrentHashMap<Integer, RemoteDevice>();
	LocalDevice localDevice = null;
	ReadWriteMonitor monitor = new ReadWriteMonitor();
	
	final Map<Integer, OctetString> networkRouters = new HashMap<Integer, OctetString>();
	final Map<String, Integer> bbmdIpToPort = new HashMap<String, Integer>();
	
	int localNetworkNumber;
	int timeout;
	int segmentTimeout;
	int segmentWindow;
	int retries;
	int localDeviceId;
	String localDeviceName;
	String localDeviceVendor;
	long defaultInterval;
	
	protected BacnetConn(BacnetLink link, Node node) {
		this.link = link;
		this.node = node;
		
		this.statnode = node.createChild(NODE_STATUS, true).setValueType(ValueType.STRING).setValue(new Value(""))
				.build();
		this.statnode.setSerializable(false);
	}
	
	public static BacnetConn buildConn(BacnetLink link, Node node) {
		Value subnetMask = node.getRoConfig("Subnet Mask");
		if (subnetMask == null) {
			subnetMask = new Value(IpNetwork.DEFAULT_SUBNET_MASK);
			node.setRoConfig("Subnet Mask", subnetMask);
		}
		Value port = node.getRoConfig("Port");
		Value localBindAddress = node.getRoConfig("Local Bind Address");
		Value isRegisteredAsForeignDevice = node.getRoConfig("Register As Foreign Device In BBMD");
		if (isRegisteredAsForeignDevice == null) {
			isRegisteredAsForeignDevice = new Value(false);
			node.setRoConfig("Register As Foreign Device In BBMD", isRegisteredAsForeignDevice);
		}
		Value bbmdIpList = node.getRoConfig("BBMD IPs With Network Number");
		if (bbmdIpList == null) {
			bbmdIpList = new Value("");
			node.setRoConfig("BBMD IPs With Network Number", bbmdIpList);
		}
		
		// MSTP transport
		Value commPort = node.getRoConfig("Comm Port ID");
		Value baud = node.getRoConfig("Baud Rate");
		Value station = node.getRoConfig("This Station ID");
		Value ferc = node.getRoConfig("Frame Error Retry Count");

		// Common attribution
		Value localNetworkNumber = node.getRoConfig("Local Network Number");
//		Value strict = node.getRoConfig("strict device comparisons");
		Value timeout = node.getRoConfig("Timeout");
		Value segmentTimeout = node.getRoConfig("Segment Timeout");
		Value segmentWindow = node.getRoConfig("Segment Window");
		Value retries = node.getRoConfig("Retries");
		Value localDeviceId = node.getRoConfig("Local Device ID");
		Value localDeviceName = node.getRoConfig("Local Device Name");
		Value localDeviceVendor = node.getRoConfig("Local Device Vendor");
		
		BacnetConn conn;
		if (localNetworkNumber != null && timeout != null && segmentTimeout != null
				&& segmentWindow != null && retries != null && localDeviceId != null && localDeviceName != null
				&& localDeviceVendor != null) {
			if (subnetMask != null && port != null && localBindAddress != null) {
				BacnetIpConn iconn = new BacnetIpConn(link, node);
				iconn.subnetMask = subnetMask.getString();
				iconn.port = port.getNumber().intValue();
				iconn.localBindAddress = localBindAddress.getString();
				iconn.isRegisteredAsForeignDevice = isRegisteredAsForeignDevice.getBool();
				iconn.bbmdIpList = bbmdIpList.getString();
				iconn.parseBroadcastManagementDevice();
				conn = iconn;
			} else if (commPort != null && baud != null && station != null && ferc != null) {
				BacnetSerialConn sconn = new BacnetSerialConn(link, node);
				sconn.commPort = commPort.getString();
				sconn.baud = baud.getNumber().intValue();
				sconn.station = station.getNumber().intValue();
				sconn.frameErrorRetryCount = ferc.getNumber().intValue();
				conn = sconn;
			} else {
				return null;
			}
			conn.localNetworkNumber = localNetworkNumber.getNumber().intValue();
			conn.timeout = timeout.getNumber().intValue();
			conn.segmentTimeout = segmentTimeout.getNumber().intValue();
			conn.segmentWindow = segmentWindow.getNumber().intValue();
			conn.retries = retries.getNumber().intValue();
			conn.localDeviceId = localDeviceId.getNumber().intValue();
			conn.localDeviceName = localDeviceName.getString();
			conn.localDeviceVendor = localDeviceVendor.getString();
			
			return conn;

		} else {
			return null;
		}
	}
	
	void init() {
		statnode.setValue(new Value(NODE_STATUS_SETTING_UP_CONNECTION));
		
		makeRemoveAction();
		makeEditAction();
		
		try {
			monitor.checkInWriter();
			Network network = getNetwork();
			Transport transport = new DefaultTransport(network);
			transport.setRetries(retries);
			transport.setTimeout(timeout);
			transport.setSegTimeout(segmentTimeout);
			transport.setSegWindow(segmentWindow);
			registerAsForeignDevice(transport);
			localDevice = new LocalDevice(localDeviceId, transport);
			try {
				localDevice.writePropertyInternal(PropertyIdentifier.objectName,new CharacterString(localDeviceName));
				localDevice.writePropertyInternal(PropertyIdentifier.vendorName, new CharacterString(localDeviceVendor));
			} catch (Exception e1) {
				LOGGER.debug("", e1);
			}
			try {
				localDevice.getEventHandler().addListener(this);
				localDevice.initialize();
				localDevice.sendGlobalBroadcast(localDevice.getIAm());
				localDevice.sendGlobalBroadcast(new WhoIsRequest());
			} catch (Exception e) {
				LOGGER.debug("", e);
				statnode.setValue(new Value("Error in initializing local device :" + e.getMessage()));
				localDevice.terminate();
				localDevice = null;
			}
			monitor.checkOutWriter();
		} catch (InterruptedException e) {
			
		}
		
		if (!NODE_STATUS_STOPPED.equals(statnode.getValue().getString())) {
			makeStopAction();
		}
		makeRestartAction();
		
		if (localDevice != null) {
			statnode.setValue(new Value(NODE_STATUS_CONNECTED));
			makeDiscoverAction();
			makeAddDiscoveredDeviceAction();
			makeAddCustomDeviceAction();
		}
		
	}
	
	abstract void registerAsForeignDevice(Transport transport);
	
	abstract Network getNetwork();
	
	void restoreLastSession() {
		init();
		if (node.getChildren() == null) {
			return;
		}

		for (Node child : node.getChildren().values()) {
			if (child.getAction() == null && !child.getName().equals(NODE_STATUS)) {
				BacnetDevice bd = new BacnetDevice(this, child, null);
				bd.restoreLastSession();
			}
		}
	}
	
	public ScheduledThreadPoolExecutor getStpe() {
		return stpe;
	}
	
	
	/////////////////////////////////////////////////////////////////////////////////////////
	// Actions
	/////////////////////////////////////////////////////////////////////////////////////////
	
	private void makeRemoveAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>(){
			@Override
			public void handle(ActionResult event) {
				remove();
			}
		});
		Node anode = node.getChild(ACTION_REMOVE, true);
		if (anode == null) {
			node.createChild(ACTION_REMOVE, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	private void makeStopAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>(){
			@Override
			public void handle(ActionResult event) {
				stop();
			}
		});
		Node anode = node.getChild(ACTION_STOP, true);
		if (anode == null) {
			node.createChild(ACTION_STOP, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	private void makeRestartAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>(){
			@Override
			public void handle(ActionResult event) {
				restart();
			}
		});
		Node anode = node.getChild(ACTION_RESTART, true);
		if (anode == null) {
			node.createChild(ACTION_RESTART, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	protected void remove() {
		stop();
		node.delete(false);
	}
	
	private void stop() {
		statnode.setValue(new Value(NODE_STATUS_STOPPED));
		try {
			monitor.checkInWriter();
			if (localDevice != null) {
				localDevice.terminate();
				localDevice = null;
//				node.removeChild(ACTION_STOP, false);
//				node.removeChild(ACTION_DISCOVER_DEVICES, false);
//				node.removeChild(ACTION_ADD_DEVICE, false);
			}
			monitor.checkOutWriter();
		} catch (InterruptedException e) {
			
		}
	}
	
	private void restart() {
		stop();
		init();
	}

	protected abstract void makeEditAction();
	
	protected void edit(ActionResult event) {
		Utils.setConfigsFromActionResult(node, event);
		setVarsFromConfigs();
		restart();
	}
	
	protected void setVarsFromConfigs() {
		localNetworkNumber = Utils.safeGetRoConfigNum(node, "Local Network Number", localNetworkNumber).intValue();
		timeout = Utils.safeGetRoConfigNum(node, "Timeout", timeout).intValue();
		segmentTimeout = Utils.safeGetRoConfigNum(node, "Segment Timeout", segmentTimeout).intValue();
		segmentWindow = Utils.safeGetRoConfigNum(node, "Segment Window", segmentWindow).intValue();
		retries = Utils.safeGetRoConfigNum(node, "Retries", retries).intValue();
		localDeviceId = Utils.safeGetRoConfigNum(node, "Local Device ID", localDeviceId).intValue();
		localDeviceName = Utils.safeGetRoConfigString(node, "Local Device Name", localDeviceName);
		localDeviceVendor = Utils.safeGetRoConfigString(node, "Local Device Vendor", localDeviceVendor);
	}
	
	private void makeDiscoverAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>(){
			@Override
			public void handle(ActionResult event) {
				discover();
			}
		});
		Node anode = node.getChild(ACTION_DISCOVER_DEVICES, true);
		if (anode == null) {
			node.createChild(ACTION_DISCOVER_DEVICES, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	private void discover() {
		try {
			monitor.checkInReader();
			if (localDevice != null) {
				localDevice.sendGlobalBroadcast(new WhoIsRequest());
			}
			monitor.checkOutReader();
		} catch (InterruptedException e) {
			
		}
		int lastLength = 0;
		for (int i=0; i<10; i++) {
			if (discovered.size() > lastLength) {
				lastLength = discovered.size();
				makeAddDiscoveredDeviceAction();
			}
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
			}
		}
	}
	
	private void makeAddDiscoveredDeviceAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>(){
			@Override
			public void handle(ActionResult event) {
				addDiscoveredDevice(event);
			}
		});
		
		act.addParameter(new Parameter("Device", ValueType.makeEnum(Utils.getDeviceEnum(discovered))));
		act.addParameter(new Parameter("Name", ValueType.STRING).setDescription("Optional - will be inferred from device if left blank"));
		act.addParameter(new Parameter("Polling Interval", ValueType.NUMBER, new Value(5)));
		//TODO headless polling?
		act.addParameter(new Parameter("Use COV", ValueType.BOOL, new Value(false)));
		Node anode = node.getChild(ACTION_ADD_DEVICE, true);
		if (anode == null) {
			node.createChild(ACTION_ADD_DEVICE, true).setAction(act).setConfig("actionGroup", new Value("Add Device")).setConfig("actionGroupSubTitle", new Value("From Discovered")).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	private void makeAddCustomDeviceAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>(){
			@Override
			public void handle(ActionResult event) {
				addCustomDevice(event);
			}
		});
		
		act.addParameter(new Parameter("Name", ValueType.STRING));
		act.addParameter(new Parameter("Instance Number", ValueType.NUMBER));
		act.addParameter(new Parameter("Polling Interval", ValueType.NUMBER, new Value(5)));
		//TODO headless polling?
		act.addParameter(new Parameter("Use COV", ValueType.BOOL, new Value(false)));
		Node anode = node.getChild(ACTION_ADD_CUSTOM_DEVICE, true);
		if (anode == null) {
			node.createChild(ACTION_ADD_CUSTOM_DEVICE, true).setAction(act).setConfig("actionGroup", new Value("Add Device")).setConfig("actionGroupSubTitle", new Value("By Instance Number")).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	private void addDiscoveredDevice(ActionResult event) {
		String devStr = event.getParameter("Device", ValueType.STRING).getString();
		String[] arr = devStr.split("\\(Instance ");
		String instStr = arr[arr.length - 1].split(" at")[0];
		Integer inst;
		try {
			inst = Integer.valueOf(instStr);
		} catch (Exception e) {
			return;
		}
		RemoteDevice d = discovered.get(inst);
		event.getParameters().remove("Device");
		event.getParameters().put("Instance Number", inst);
		addDevice(event, d, inst);
	}
	
	private void addCustomDevice(ActionResult event) {
		int inst = event.getParameter("Instance Number", ValueType.NUMBER).getNumber().intValue();
		RemoteDevice d = null;
		try {
			monitor.checkInReader();
			if (localDevice == null) {
				return;
			}
			try {
				d = localDevice.getRemoteDeviceBlocking(inst);
			} catch (BACnetException e) {
				LOGGER.debug("" ,e);
			}
			monitor.checkOutReader();
		} catch (InterruptedException e) {
			
		}
		addDevice(event, d, inst);
	}
	
	private void addDevice(ActionResult event, RemoteDevice d, int instanceNum) {
		
		String name = null;
		Value nameVal = event.getParameter("Name");
		if (nameVal != null) {
			name = nameVal.getString();
		}
		if ((name == null || name.trim().isEmpty()) && d != null) {
			name = d.getName();
		}
		if (name == null || name.trim().isEmpty()) {
			return;
		}
		
		Node child = node.createChild(name, true).build();
		Utils.setConfigsFromActionResult(child, event);
		BacnetDevice bd = new BacnetDevice(this, child, d);
		bd.init();
	}
	

	///////////////////////////////////////////////////////////////////////////////////////////
	// Listener Methods
	///////////////////////////////////////////////////////////////////////////////////////////
	
	@Override
	public void listenerException(Throwable e) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void iAmReceived(RemoteDevice d) {
		LOGGER.info("iAm recieved: " + d);
		discovered.put(d.getInstanceNumber(), d);
		
	}

	@Override
	public boolean allowPropertyWrite(Address from, BACnetObject obj, PropertyValue pv) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void propertyWritten(Address from, BACnetObject obj, PropertyValue pv) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void iHaveReceived(RemoteDevice d, RemoteObject o) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void covNotificationReceived(UnsignedInteger subscriberProcessIdentifier,
			ObjectIdentifier initiatingDeviceIdentifier, ObjectIdentifier monitoredObjectIdentifier,
			UnsignedInteger timeRemaining, SequenceOf<PropertyValue> listOfValues) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void eventNotificationReceived(UnsignedInteger processIdentifier,
			ObjectIdentifier initiatingDeviceIdentifier, ObjectIdentifier eventObjectIdentifier, TimeStamp timeStamp,
			UnsignedInteger notificationClass, UnsignedInteger priority, EventType eventType,
			CharacterString messageText, NotifyType notifyType, Boolean ackRequired, EventState fromState,
			EventState toState, NotificationParameters eventValues) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void textMessageReceived(ObjectIdentifier textMessageSourceDevice, Choice messageClass,
			MessagePriority messagePriority, CharacterString message) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void privateTransferReceived(Address from, UnsignedInteger vendorId, UnsignedInteger serviceNumber,
			Sequence serviceParameters) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void reinitializeDevice(Address from, ReinitializedStateOfDevice reinitializedStateOfDevice) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void synchronizeTime(Address from, DateTime dateTime, boolean utc) {
		// TODO Auto-generated method stub
		
	}


}
