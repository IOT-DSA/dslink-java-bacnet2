package bacnet;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.tuple.Pair;
import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.NodeBuilder;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.actions.ResultType;
import org.dsa.iot.dslink.node.actions.table.Row;
import org.dsa.iot.dslink.node.actions.table.Table;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.Objects;
import org.dsa.iot.dslink.util.handler.Handler;
import org.dsa.iot.dslink.util.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.ServiceFuture;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.service.acknowledgement.GetEventInformationAck;
import com.serotonin.bacnet4j.service.acknowledgement.GetEventInformationAck.EventSummary;
import com.serotonin.bacnet4j.service.confirmed.AcknowledgeAlarmRequest;
import com.serotonin.bacnet4j.service.confirmed.GetEventInformationRequest;
import com.serotonin.bacnet4j.service.confirmed.SubscribeCOVRequest;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.constructed.BACnetArray;
import com.serotonin.bacnet4j.type.constructed.DateTime;
import com.serotonin.bacnet4j.type.constructed.EventTransitionBits;
import com.serotonin.bacnet4j.type.constructed.ObjectPropertyReference;
import com.serotonin.bacnet4j.type.constructed.PropertyValue;
import com.serotonin.bacnet4j.type.constructed.SequenceOf;
import com.serotonin.bacnet4j.type.constructed.TimeStamp;
import com.serotonin.bacnet4j.type.enumerated.EventState;
import com.serotonin.bacnet4j.type.enumerated.EventType;
import com.serotonin.bacnet4j.type.enumerated.NotifyType;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.notificationParameters.NotificationParameters;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.Time;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import com.serotonin.bacnet4j.type.primitive.Boolean;
import com.serotonin.bacnet4j.type.primitive.CharacterString;
import com.serotonin.bacnet4j.util.RequestUtils;


public class BacnetDevice {
	private static final Logger LOGGER = LoggerFactory.getLogger(BacnetDevice.class);

	static final String NODE_STATUS = "STATUS";
	static final String NODE_EVENTS = "EVENTS";
	static final String ACTION_REMOVE = "remove";
	static final String ACTION_EDIT = "edit";
	static final String ACTION_ADD_FOLDER = "add folder";
	static final String ACTION_DISCOVER_OBJECTS = "discover objects";
	static final String ACTION_ADD_OBJECT = "add object";
	static final String ACTION_STOP = "stop";
	static final String ACTION_RESTART = "restart";
	static final String ACTION_CLEAR = "clear";
	static final String ACTION_GET_EVENTS = "get event information";
	static final String ACTION_ACKNOWLEDGE_ALARM = "acknowledge alarm";
	static final String EVENT_ACTION_ACKNOWLEDGE = "acknowledge";
	static final String EVENT_ACTION_DISMISS = "dismiss";

	BacnetConn conn;
	private Node node;
	private final Node statnode;
	private final Node eventsnode;
	private int eventCount = 0;

	RemoteDevice remoteDevice;
	ReadWriteMonitor monitor = new ReadWriteMonitor();

	int instanceNumber;
	private double pollingIntervalSeconds;
	private boolean covConfirmed;
	private long covLifetime;

	Set<BacnetObject> objects = new HashSet<BacnetObject>();
	private Map<BacnetProperty, ObjectPropertyReference> subscribed = new ConcurrentHashMap<BacnetProperty, ObjectPropertyReference>();
	private ScheduledFuture<?> pollingFuture = null;
	Object futureLock = new Object();

	public BacnetDevice(BacnetConn conn, Node node, RemoteDevice d) {
		this.conn = conn;
		this.node = node;

		try {
			monitor.checkInWriter();
			this.remoteDevice = d;
			monitor.checkOutWriter();
		} catch (InterruptedException e) {

		}

		instanceNumber = Utils.getAndMaybeSetRoConfigNum(node, "Instance Number", 0).intValue();
		pollingIntervalSeconds = Utils.getAndMaybeSetRoConfigNum(node, "Polling Interval", 5).doubleValue();
		covConfirmed = Utils.getAndMaybeSetRoConfigBool(node, "Get Confirmed COV Notifications", false);
		covLifetime = Utils.getAndMaybeSetRoConfigNum(node, "COV Lifetime", 0).longValue();

		this.statnode = node.createChild(NODE_STATUS, true).setValueType(ValueType.STRING).setValue(new Value(""))
				.build();
		this.statnode.setSerializable(false);
		
		this.eventsnode = node.createChild(NODE_EVENTS, true).build();
		this.eventsnode.setSerializable(false);

		conn.devices.add(this);
	}

	public void restoreLastSession() {
		init();
		restoreFolder(node);
	}

	private void restoreFolder(Node fnode) {
		if (fnode.getChildren() == null) {
			return;
		}
		for (Node child : fnode.getChildren().values()) {
			Value restype = child.getRoConfig("restoreAs");
			if (restype != null && "folder".equals(restype.getString())) {
				makeFolderActions(child);
				restoreFolder(child);
			} else if (restype != null && "object".equals(restype.getString())) {
				String typestr = Utils.safeGetRoConfigString(child, "Object Type", null);
				Number instnum = Utils.safeGetRoConfigNum(child, "Instance Number", null);
				ObjectIdentifier oid = null;
				if (typestr != null && instnum != null) {
					try {
						ObjectType type = ObjectType.forName(typestr);
						oid = new ObjectIdentifier(type, instnum.intValue());
					} catch (Exception e) {
					}
				}
				if (oid != null) {
					BacnetObject bo = new BacnetObject(this, child, oid);
					bo.restoreLastSession();
				} else {
					child.delete(false);
				}
			} else if (child.getAction() == null && !child.getName().equals(NODE_STATUS) && !child.getName().equals(NODE_EVENTS)) {
				child.delete(false);
			}
		}
	}

	public void init() {
		Objects.getDaemonThreadPool().schedule(new Runnable() {
			@Override
			public void run() {
				try {
					monitor.checkInWriter();
					if (remoteDevice == null) {
						statnode.setValue(new Value("Connecting"));
						try {
							conn.monitor.checkInReader();
							if (conn.localDevice != null) {
								try {
									remoteDevice = conn.localDevice.getRemoteDeviceBlocking(instanceNumber);
								} catch (BACnetException e) {
									statnode.setValue(new Value("Failed to Connect"));
									LOGGER.debug("", e);
								}
							} else {
								statnode.setValue(new Value("Connection Down"));
							}
							conn.monitor.checkOutReader();
						} catch (InterruptedException e) {

						}
					}
					if (remoteDevice != null) {
						statnode.setValue(new Value("Ready"));
					}
					monitor.checkOutWriter();
				} catch (InterruptedException e) {

				}
			}
		}, 0, TimeUnit.MILLISECONDS);
		
		makeFolderActions(node);
		makeEditAction();
		makeStopAction();
		makeRestartAction();
		makeEventsNodeActions();
	}

	/////////////////////////////////////////////////////////////////////////////////////////
	// Polling
	/////////////////////////////////////////////////////////////////////////////////////////

	public void subscribeProperty(BacnetProperty prop) {
		ObjectPropertyReference opr = new ObjectPropertyReference(prop.oid, prop.pid);
		subscribed.put(prop, opr);
		startPolling();
	}

	public boolean unsubscribeProperty(BacnetProperty prop) {
		boolean wasSubbed = (subscribed.remove(prop) != null);
		if (subscribed.isEmpty()) {
			stopPolling();
		}
		return wasSubbed;
	}

	public void subscribeObjectCov(BacnetObject obj) {
		try {
			monitor.checkInReader();
			if (remoteDevice != null) {
				try {
					conn.monitor.checkInReader();
					if (conn.localDevice != null) {
						LOGGER.info("subscribing to cov for device " + node.getName() + ", object " + obj.oid);
						conn.localDevice.send(remoteDevice,
								new SubscribeCOVRequest(new UnsignedInteger(conn.subscriberId), obj.oid,
										Boolean.valueOf(covConfirmed), new UnsignedInteger(covLifetime)));
					}
					conn.monitor.checkOutReader();
				} catch (InterruptedException e) {

				}
			}
			monitor.checkOutReader();
		} catch (InterruptedException e) {

		}
	}

	public void unsubscribeObjectCov(BacnetObject obj) {
		try {
			monitor.checkInReader();
			if (remoteDevice != null) {
				try {
					conn.monitor.checkInReader();
					if (conn.localDevice != null) {
						LOGGER.info("unsubscribing from cov for device " + node.getName() + ", object " + obj.oid);
						conn.localDevice.send(remoteDevice,
								new SubscribeCOVRequest(new UnsignedInteger(conn.subscriberId), obj.oid, null, null));
					}
					conn.monitor.checkOutReader();
				} catch (InterruptedException e) {

				}
			}
			monitor.checkOutReader();
		} catch (InterruptedException e) {

		}
	}

	public void covNotificationReceived(ObjectIdentifier monitoredObjectIdentifier, UnsignedInteger timeRemaining,
			SequenceOf<PropertyValue> listOfValues) {
		for (BacnetObject obj : objects) {
			if (monitoredObjectIdentifier.equals(obj.oid)) {
				obj.covNotificationReceived(timeRemaining, listOfValues);
			}
		}
	}

	private void startPolling() {
		synchronized (futureLock) {
			if (pollingFuture != null) {
				return;
			}
			long interval = (long) (pollingIntervalSeconds * 1000);
			pollingFuture = conn.getStpe().scheduleWithFixedDelay(new Runnable() {

				@Override
				public void run() {
					readProperties();
				}

			}, 0, interval, TimeUnit.MILLISECONDS);
		}
	}

	private void stopPolling() {
		synchronized (futureLock) {
			if (pollingFuture != null) {
				pollingFuture.cancel(false);
				pollingFuture = null;
			}
		}
	}

	private void readProperties() {
		List<ObjectPropertyReference> oprs = new ArrayList<ObjectPropertyReference>(subscribed.size());
		List<BacnetProperty> props = new ArrayList<BacnetProperty>(subscribed.size());
		for (Entry<BacnetProperty, ObjectPropertyReference> entry : subscribed.entrySet()) {
			oprs.add(entry.getValue());
			props.add(entry.getKey());
		}
		List<Pair<ObjectPropertyReference, Encodable>> results = null;
		try {
			monitor.checkInReader();
			if (remoteDevice != null) {
				try {
					conn.monitor.checkInReader();
					if (conn.localDevice != null) {
						try {
							// LOGGER.info("Sending Read Properties Request to
							// Device: " + node.getName());
							results = RequestUtils.readProperties(conn.localDevice, remoteDevice, oprs, null);
							// if (results != null) LOGGER.info("Recieved Read
							// Properties Response from Device: " +
							// node.getName());
						} catch (BACnetException e) {
							LOGGER.debug("", e);
						}
					}
					conn.monitor.checkOutReader();
				} catch (InterruptedException e) {

				}
			}
			monitor.checkOutReader();
		} catch (InterruptedException e) {

		}
		if (results == null) {
			return;
		}
		if (results.size() != props.size()) {
			LOGGER.error("Number of results doesn't match number of requests");
			return;
		}
		for (int i = 0; i < results.size(); i++) {
			Pair<ObjectPropertyReference, Encodable> result = results.get(i);
			// ObjectPropertyReference opr = result.getLeft();
			Encodable val = result.getRight();
			BacnetProperty prop = props.get(i);
			prop.updateValue(val);
		}
	}

	/////////////////////////////////////////////////////////////////////////////////////////
	// Actions
	/////////////////////////////////////////////////////////////////////////////////////////

	private void makeFolderActions(Node fnode) {
		makeRemoveAction(fnode);
		makeAddFolderAction(fnode);
		makeDiscoverObjectsAction(fnode);
		makeAddObjectAction(fnode);
	}

	private void makeRemoveAction(final Node fnode) {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			@Override
			public void handle(ActionResult event) {
				remove(fnode);
			}
		});
		Node anode = fnode.getChild(ACTION_REMOVE, true);
		if (anode == null) {
			fnode.createChild(ACTION_REMOVE, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}

	private void remove(Node fnode) {
		if (node.equals(fnode)) {
			stop();
		}
		conn.devices.remove(this);
		fnode.delete(false);
	}

	private void makeAddFolderAction(final Node fnode) {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			@Override
			public void handle(ActionResult event) {
				addFolder(fnode, event);
			}
		});
		act.addParameter(new Parameter("Name", ValueType.STRING));
		Node anode = fnode.getChild(ACTION_ADD_FOLDER, true);
		if (anode == null) {
			fnode.createChild(ACTION_ADD_FOLDER, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}

	private void addFolder(Node fnode, ActionResult event) {
		String name = event.getParameter("Name", ValueType.STRING).getString();
		Node child = fnode.createChild(name, true).setRoConfig("restoreAs", new Value("folder")).build();
		makeFolderActions(child);
	}

	private void makeDiscoverObjectsAction(final Node fnode) {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			@Override
			public void handle(ActionResult event) {
				discoverObjects(fnode);
			}
		});
		Node anode = fnode.getChild(ACTION_DISCOVER_OBJECTS, true);
		if (anode == null) {
			fnode.createChild(ACTION_DISCOVER_OBJECTS, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}

	private void makeAddObjectAction(final Node fnode) {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			@Override
			public void handle(ActionResult event) {
				addObject(fnode, event);
			}
		});
		act.addParameter(new Parameter("Object Type", ValueType.makeEnum(Utils.getObjectTypeList())));
		act.addParameter(new Parameter("Instance Number", ValueType.NUMBER));
		act.addParameter(new Parameter("Use COV", ValueType.BOOL));
		act.addParameter(new Parameter("Enable Headless Polling", ValueType.BOOL, new Value(false)));
		act.addParameter(new Parameter("Write Priority", ValueType.NUMBER, new Value(16)));
		Node anode = fnode.getChild(ACTION_ADD_OBJECT, true);
		if (anode == null) {
			fnode.createChild(ACTION_ADD_OBJECT, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}

	private void discoverObjects(Node fnode) {
		SequenceOf<ObjectIdentifier> oids = null;
		try {
			monitor.checkInReader();
			if (remoteDevice != null) {
				try {
					conn.monitor.checkInReader();
					if (conn.localDevice != null) {
						try {
							LOGGER.info("Sending Object List Request for Device: " + node.getName());
							oids = RequestUtils.getObjectList(conn.localDevice, remoteDevice);
							if (oids != null)
								LOGGER.info("Recieved Object List Response from Device " + node.getName());
						} catch (BACnetException e) {
							LOGGER.debug("", e);
						}
					}
					conn.monitor.checkOutReader();
				} catch (InterruptedException e) {

				}
			}
			monitor.checkOutReader();
		} catch (InterruptedException e) {

		}
		if (oids == null) {
			return;
		}
		for (ObjectIdentifier oid : oids) {
			String name = oid.toString();
			NodeBuilder b = fnode.createChild(name, true).setRoConfig("restoreAs", new Value("object"))
					.setRoConfig("Object Type", new Value(oid.getObjectType().toString()))
					.setRoConfig("Instance Number", new Value(oid.getInstanceNumber()))
					.setRoConfig("Use COV", new Value(false))
					.setRoConfig("Enable Headless Polling", new Value(false))
					.setRoConfig("Write Priority", new Value(16))
					.setValueType(ValueType.STRING).setValue(new Value(""));
			BacnetObject bo = new BacnetObject(this, b.getChild(), oid);
			bo.init();
			b.build();
		}
	}

	private void addObject(Node fnode, ActionResult event) {
		String typeStr = event.getParameter("Object Type").getString();
		int instNum = event.getParameter("Instance Number", ValueType.NUMBER).getNumber().intValue();
		boolean useCov = event.getParameter("Use COV", ValueType.BOOL).getBool();
		boolean headless = event.getParameter("Enable Headless Polling", ValueType.BOOL).getBool();
		int writePriority = event.getParameter("Write Priority", ValueType.NUMBER).getNumber().intValue();
		if (writePriority < 1) {
			writePriority = 1;
		} else if (writePriority > 16) {
			writePriority = 16;
		}
		ObjectType type;
		try {
			type = ObjectType.forName(typeStr);
		} catch (Exception e) {
			return;
		}
		ObjectIdentifier oid = new ObjectIdentifier(type, instNum);
		String name = oid.toString();
		NodeBuilder b = fnode.createChild(name, true).setRoConfig("restoreAs", new Value("object"))
				.setRoConfig("Object Type", new Value(oid.getObjectType().toString()))
				.setRoConfig("Instance Number", new Value(oid.getInstanceNumber()))
				.setRoConfig("Use COV", new Value(useCov))
				.setRoConfig("Enable Headless Polling", new Value(headless))
				.setRoConfig("Write Priority", new Value(writePriority))
				.setValueType(ValueType.STRING).setValue(new Value(""));
		BacnetObject bo = new BacnetObject(this, b.getChild(), oid);
		bo.init();
		b.build();
	}

	private void makeStopAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
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
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
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

	private void makeEditAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			@Override
			public void handle(ActionResult event) {
				edit(event);
			}
		});
		act.addParameter(new Parameter("Instance Number", ValueType.NUMBER, new Value(instanceNumber)));
		act.addParameter(new Parameter("Polling Interval", ValueType.NUMBER, new Value(pollingIntervalSeconds)));
		act.addParameter(new Parameter("Get Confirmed COV Notifications", ValueType.BOOL, new Value(covConfirmed)));
		act.addParameter(new Parameter("COV Lifetime", ValueType.NUMBER, new Value(covLifetime)));
		Node anode = node.getChild(ACTION_EDIT, true);
		if (anode == null) {
			node.createChild(ACTION_EDIT, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}

	private void edit(ActionResult event) {
		Utils.setConfigsFromActionResult(node, event);
		instanceNumber = Utils.safeGetRoConfigNum(node, "Instance Number", instanceNumber).intValue();
		pollingIntervalSeconds = Utils.safeGetRoConfigNum(node, "Polling Interval", pollingIntervalSeconds)
				.doubleValue();
		covConfirmed = Utils.safeGetRoConfigBool(node, "Get Confirmed COV Notifications", covConfirmed);
		covLifetime = Utils.safeGetRoConfigNum(node, "COV Lifetime", covLifetime).longValue();

		restart();
	}

	private void restart() {
		stop();
		init();
	}

	private void stop() {
		try {
			monitor.checkInWriter();
			remoteDevice = null;
			statnode.setValue(new Value("Stopped"));
			monitor.checkOutWriter();
		} catch (InterruptedException e) {

		}
	}
	
	private void makeEventsNodeActions() {
		makeGetEventInfoAction();
		makeAcknowledgeAction();
		makeClearEventsAction();
	}
	
	private void makeGetEventInfoAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			@Override
			public void handle(ActionResult event) {
				getEventInfo(event);
			}
		});
		act.addResult(new Parameter("Object Identifier", ValueType.STRING));
		act.addResult(new Parameter("Event State", ValueType.STRING));
		act.addResult(new Parameter("Notify Type", ValueType.STRING));
		act.addResult(new Parameter("Acknowledged Transitions: To-Offnormal", ValueType.BOOL));
		act.addResult(new Parameter("Acknowledged Transitions: To-Fault", ValueType.BOOL));
		act.addResult(new Parameter("Acknowledged Transitions: To-Normal", ValueType.BOOL));
		act.addResult(new Parameter("Event Timestamps: To-Offnormal", ValueType.STRING));
		act.addResult(new Parameter("Event Timestamps: To-Fault", ValueType.STRING));
		act.addResult(new Parameter("Event Timestamps: To-Normal", ValueType.STRING));
		act.addResult(new Parameter("Event Enable: To-Offnormal", ValueType.BOOL));
		act.addResult(new Parameter("Event Enable: To-Fault", ValueType.BOOL));
		act.addResult(new Parameter("Event Enable: To-Normal", ValueType.BOOL));
		act.addResult(new Parameter("Event Priorities: To-Offnormal", ValueType.NUMBER));
		act.addResult(new Parameter("Event Priorities: To-Fault", ValueType.NUMBER));
		act.addResult(new Parameter("Event Priorities: To-Normal", ValueType.NUMBER));
		act.setResultType(ResultType.TABLE);
		eventsnode.createChild(ACTION_GET_EVENTS, true).setAction(act).build().setSerializable(false);
	}
	
	private void getEventInfo(ActionResult event) {
		boolean moreEvents = true;
		ObjectIdentifier lastRecieved = null;
		Table table = event.getTable();
		while (moreEvents) {
			moreEvents = false;
			GetEventInformationRequest request = new GetEventInformationRequest(lastRecieved);
			ServiceFuture sf = Utils.sendConfirmedRequest(conn, this, request);
			try {
				GetEventInformationAck ack = sf.get();
				for (EventSummary es : ack.getListOfEventSummaries()) {
					lastRecieved = es.getObjectIdentifier();
					String oid = lastRecieved.toString();
					String eventState = es.getEventState().toString();
					String notifyType = es.getNotifyType().toString();
					EventTransitionBits ackTrans = es.getAcknowledgedTransitions();
					boolean ackTransOffnormal = ackTrans.isToOffnormal();
					boolean ackTransFault = ackTrans.isToFault();
					boolean ackTransNormal = ackTrans.isToNormal();
					BACnetArray<TimeStamp> timestamps = es.getEventTimeStamps();
					String tsOffnormal = timestampToString(timestamps.getBase1(1));
					String tsFault = timestampToString(timestamps.getBase1(2));
					String tsNormal = timestampToString(timestamps.getBase1(3));
					EventTransitionBits evEnable = es.getEventEnable();
					boolean evEnableOffnormal = evEnable.isToOffnormal();
					boolean evEnableFault = evEnable.isToFault();
					boolean evEnableNormal = evEnable.isToNormal();
					BACnetArray<UnsignedInteger> priorities = es.getEventPriorities();
					int prioOffnormal = priorities.getBase1(1).intValue();
					int prioFault = priorities.getBase1(2).intValue();
					int prioNormal = priorities.getBase1(3).intValue();

					Row row = Row.make(new Value(oid), new Value(eventState), new Value(notifyType),
							new Value(ackTransOffnormal), new Value(ackTransFault), new Value(ackTransNormal),
							new Value(tsOffnormal), new Value(tsFault), new Value(tsNormal),
							new Value(evEnableOffnormal), new Value(evEnableFault), new Value(evEnableNormal),
							new Value(prioOffnormal), new Value(prioFault), new Value(prioNormal));
					table.addRow(row);
				}
				moreEvents = ack.getMoreEvents().booleanValue();
			} catch (BACnetException e) {
				LOGGER.debug("", e);
			}
		}
	}
	
	private void makeAcknowledgeAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			@Override
			public void handle(ActionResult event) {
				acknowledge(event);
			}
		});
		act.addParameter(new Parameter("Acknowledging Process Identifier", ValueType.NUMBER));
		act.addParameter(new Parameter("Event Object Type", ValueType.makeEnum(Utils.getObjectTypeList())));
		act.addParameter(new Parameter("Event Object Instance", ValueType.NUMBER));
		act.addParameter(new Parameter("Event State Acknowledged", ValueType.makeEnum(Utils.getEnumeratedStateList(EventState.class))));
		act.addParameter(new Parameter("Timestamp", ValueType.STRING));
		act.addParameter(new Parameter("Acknowledgement Source", ValueType.STRING));
		eventsnode.createChild(ACTION_ACKNOWLEDGE_ALARM, true).setAction(act).build().setSerializable(false);
	}
	
	private void acknowledge(ActionResult event) {
		UnsignedInteger acknowledgingProcessIdentifier = new UnsignedInteger(event.getParameter("Acknowledging Process Identifier", ValueType.NUMBER).getNumber().intValue());
		ObjectType ot = ObjectType.forName(event.getParameter("Event Object Type").getString());
		int inst = event.getParameter("Event Object Instance", ValueType.NUMBER).getNumber().intValue();
		ObjectIdentifier eventObjectIdentifier = new ObjectIdentifier(ot, inst);
		EventState eventStateAcknowledged = EventState.forName(event.getParameter("Event State Acknowledged").getString());
		String tsstr = event.getParameter("Timestamp", ValueType.STRING).getString();
		TimeStamp timeStamp = null;
		try {
			int i = Integer.parseUnsignedInt(tsstr);
			timeStamp = new TimeStamp(new UnsignedInteger(i));
		} catch (NumberFormatException e) {
		}
		if (timeStamp == null) {
			DateFormat dateFormat = new W3CDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
			try {
				Date d = dateFormat.parse(tsstr);
				timeStamp = new TimeStamp(new DateTime(d.getTime()));
			} catch (ParseException e) {
			}
		}
		if (timeStamp == null) {
			Time t = TypeUtils.formatTime(new Value(tsstr));
			timeStamp = new TimeStamp(t);
		}
		
		CharacterString acknowledgmentSource = new CharacterString(event.getParameter("Acknowledgement Source", new Value("")).getString());
		TimeStamp timeOfAcknowledgment = new TimeStamp(new DateTime(new Date().getTime()));
		AcknowledgeAlarmRequest request =  new AcknowledgeAlarmRequest(acknowledgingProcessIdentifier, eventObjectIdentifier, eventStateAcknowledged , timeStamp, acknowledgmentSource, timeOfAcknowledgment);
		Utils.sendConfirmedRequest(conn, this, request);
	}
	
	private void makeClearEventsAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			@Override
			public void handle(ActionResult event) {
				clearEvents();
			}		
		});
		eventsnode.createChild(ACTION_CLEAR, true).setAction(act).build().setSerializable(false);
	}
	
	private void clearEvents() {
		synchronized (eventsnode) {
			if (eventsnode.getChildren() == null) {
				return;
			}
			for (Node child: eventsnode.getChildren().values()) {
				if (child.getAction() == null) {
					child.delete(false);
				}
			}
			eventCount = 0;
		}
	}
	
	private void makeEventActions(Node enode) {
		makeAcknowledgeAction(enode);
		makeDismissAction(enode);
	}
	
	private void makeAcknowledgeAction(final Node enode) {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			@Override
			public void handle(ActionResult event) {
				acknowledge(enode, event);
			}
		});
		act.addParameter(new Parameter("Acknowledging Process Identifier", ValueType.NUMBER));
		act.addParameter(new Parameter("Acknowledgement Source", ValueType.STRING));
		enode.createChild(EVENT_ACTION_ACKNOWLEDGE, true).setAction(act).build().setSerializable(false);
	}
	
	private void acknowledge(Node enode, ActionResult event) {
		JsonObject jo = enode.getValue().getMap();
		if (event.getParameter("Acknowledging Process Identifier") == null) {
			event.getParameters().put("Acknowledging Process Identifier", jo.get("Process Identifier"));
		}
		String[] arr = ((String) jo.get("Event Object Identifier")).split(" ");
		int instnum = Integer.parseInt(arr[arr.length - 1]);
		String type = arr[0];
		event.getParameters().put("Event Object Type", type);
		event.getParameters().put("Event Object Instance", instnum);
		event.getParameters().put("Event State Acknowledged", jo.get("To State"));
		event.getParameters().put("Timestamp", jo.get("Timestamp"));
		acknowledge(event);
	}
	
	private void makeDismissAction(final Node enode) {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			@Override
			public void handle(ActionResult event) {
				dismissEvent(enode);
			}
		});
		enode.createChild(EVENT_ACTION_DISMISS, true).setAction(act).build().setSerializable(false);
	}
	
	private void dismissEvent(Node enode) {
		node.delete(false);
	}
	
	
	/////////////////////////////////////////////////////////////////////////////////////////
	// Event Handling
	/////////////////////////////////////////////////////////////////////////////////////////

	
	public void eventNotificationReceived(UnsignedInteger processIdentifier, ObjectIdentifier eventObjectIdentifier, 
			TimeStamp timeStamp, UnsignedInteger notificationClass, UnsignedInteger priority, EventType eventType,
			CharacterString messageText, NotifyType notifyType, Boolean ackRequired, EventState fromState,
			EventState toState, NotificationParameters eventValues) {
		JsonObject jo = new JsonObject();
		jo.put("Process Identifier", processIdentifier.intValue());
		jo.put("Event Object Identifier", eventObjectIdentifier.toString());
		jo.put("Timestamp", timestampToString(timeStamp));
		jo.put("Notification Class", notificationClass.intValue());
		jo.put("Priority", priority.intValue());
		jo.put("Event Type", eventType.toString());
		if (messageText != null) {
			jo.put("Message Text", messageText.toString());
		}
		jo.put("Notify Type", notifyType.toString());
		if (ackRequired != null) {
			jo.put("Ack Required", ackRequired.booleanValue());
		}
		if (fromState != null) {
			jo.put("From State", fromState.toString());
		}
		jo.put("To State", toState.toString());
		if (eventValues != null) {
			jo.put("Event State", eventValues.toString());
		}
		Value val = new Value(jo);
		Node enode;
		synchronized (eventsnode) {
			eventCount += 1;
			enode = eventsnode.createChild(Integer.toString(eventCount), true).setValueType(ValueType.MAP).setValue(val).build();
		}
		if (enode != null) {
			makeEventActions(enode);
		}
	}
	
	private String timestampToString(TimeStamp ts) {
		if (ts.isSequenceNumber()) {
			return ts.getSequenceNumber().bigIntegerValue().toString();
		} else if (ts.isTime()) {
			return TypeUtils.parseTime(ts.getTime());
		} else if (ts.isDateTime()) {
			DateFormat dateFormat = new W3CDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
			return dateFormat.format(ts.getDateTime().getGC().getTime());
		}
		return null;
	}
	
	private static class W3CDateFormat extends SimpleDateFormat {
		private static final long serialVersionUID = 1L;

		public W3CDateFormat(String string) {
			super(string);
		}

		public Date parse(String source, ParsePosition pos) {    
	        return super.parse(source.replaceFirst(":(?=[0-9]{2}$)",""),pos);
	    }
	}
	
}
