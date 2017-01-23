package bacnet;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.dsa.iot.dslink.node.Node;
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
import org.dsa.iot.dslink.util.json.JsonArray;
import org.dsa.iot.dslink.util.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.ServiceFuture;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.service.acknowledgement.GetAlarmSummaryAck;
import com.serotonin.bacnet4j.service.acknowledgement.GetAlarmSummaryAck.AlarmSummary;
import com.serotonin.bacnet4j.service.acknowledgement.GetEventInformationAck;
import com.serotonin.bacnet4j.service.acknowledgement.GetEventInformationAck.EventSummary;
import com.serotonin.bacnet4j.service.confirmed.AcknowledgeAlarmRequest;
import com.serotonin.bacnet4j.service.confirmed.GetAlarmSummaryRequest;
import com.serotonin.bacnet4j.service.confirmed.GetEventInformationRequest;
import com.serotonin.bacnet4j.type.constructed.DateTime;
import com.serotonin.bacnet4j.type.constructed.EventTransitionBits;
import com.serotonin.bacnet4j.type.constructed.TimeStamp;
import com.serotonin.bacnet4j.type.enumerated.EventState;
import com.serotonin.bacnet4j.type.enumerated.NotifyType;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.primitive.CharacterString;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import com.serotonin.bacnet4j.util.PropertyReferences;

public class DeviceNode extends DeviceFolder {
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(DeviceNode.class);
	}

	final Node statnode;
	final Node eventnode;
	boolean enabled;
	RemoteDevice device;
	long interval;
	CovType covType;

	private final ScheduledThreadPoolExecutor deviceStpe;
	private final ConcurrentMap<ObjectIdentifier, BacnetPoint> subscribedPoints = new ConcurrentHashMap<ObjectIdentifier, BacnetPoint>();
	private ScheduledFuture<?> pollingFuture = null;
	private ScheduledFuture<?> reconnectFuture = null;
	private int retryDelay = 1;

	DeviceNode(BacnetConn conn, Node node, RemoteDevice d) {
		super(conn, node);
		this.device = d;
		this.root = this;
		conn.deviceNodes.add(this);

		if (node.getChild("STATUS") != null) {
			this.statnode = node.getChild("STATUS");
			enabled = new Value("enabled").equals(statnode.getValue());
		} else {
			this.statnode = node.createChild("STATUS").setValueType(ValueType.STRING).setValue(new Value("enabled"))
					.build();
			enabled = true;
		}

		if (node.getChild("EVENTS") != null) {
			this.eventnode = node.getChild("EVENTS");
		} else {
			this.eventnode = node.createChild("EVENTS").setValueType(ValueType.ARRAY)
					.setValue(new Value(new JsonArray())).build();
		}

		if (d == null && !"disabled".equals(statnode.getValue().getString())) {
			statnode.setValue(new Value("not connected"));
			enabled = false;
		}

		if (!"disabled".equals(statnode.getValue().getString())) {
			Action act = new Action(Permission.READ, new Handler<ActionResult>() {
				public void handle(ActionResult event) {
					disable(true);
				}
			});
			node.createChild("disable").setAction(act).build().setSerializable(false);

			makeAlarmActions();
		}
		if (!"enabled".equals(statnode.getValue().getString())) {
			Action act = new Action(Permission.READ, new Handler<ActionResult>() {
				public void handle(ActionResult event) {
					enable(true);
				}
			});
			node.createChild("enable").setAction(act).build().setSerializable(false);
		}

		this.interval = node.getAttribute("polling interval").getNumber().longValue();
		this.covType = CovType.NONE;
		try {
			this.covType = CovType.valueOf(node.getAttribute("cov usage").getString());
		} catch (Exception e) {
		}

		this.deviceStpe = conn.getDaemonThreadPool();

		makeEditAction();

		if ("not connected".equals(statnode.getValue().getString())) {
			scheduleRetry();
		}

	}

	ScheduledThreadPoolExecutor getDaemonThreadPool() {
		return deviceStpe;
	}

	void enable(boolean userDriven) {
		if (reconnectFuture != null) {
			reconnectFuture.cancel(false);
			reconnectFuture = null;
		}
		if (userDriven)
			retryDelay = 1;
		for (Node child : node.getChildren().values()) {
			if (child.getAction() == null && child != statnode) {
				child.removeConfig("disconnectedTs");
			}
		}
		enabled = true;
		if (pollingFuture == null)
			startPolling();

		if (device == null) {
			String mac = node.getAttribute("MAC address").getString();
			int instNum = node.getAttribute("instance number").getNumber().intValue();
			int netNum = node.getAttribute("network number").getNumber().intValue();
			String linkMac = node.getAttribute("link service MAC").getString();
			CovType covtype = CovType.NONE;
			try {
				covtype = CovType.valueOf(node.getAttribute("cov usage").getString());
			} catch (Exception e1) {
			}
			int covlife = node.getAttribute("cov lease time (minutes)").getNumber().intValue();
			final RemoteDevice d = conn.getDevice(mac, instNum, netNum, linkMac, interval, covtype, covlife);
			conn.getDeviceProps(d);
			device = d;
		}
		statnode.setValue(new Value("enabled"));
		node.removeChild("enable");
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			public void handle(ActionResult event) {
				disable(true);
			}
		});
		node.createChild("disable").setAction(act).build().setSerializable(false);

		makeAlarmActions();

		if (device == null) {
			disable(false);
			scheduleRetry();
		} else {
			retryDelay = 1;
		}
	}

	private void disable(boolean userDriven) {
		enabled = false;
		stopPolling();
		if (userDriven) {
			statnode.setValue(new Value("disabled"));
			if (reconnectFuture != null) {
				reconnectFuture.cancel(false);
				reconnectFuture = null;
			}
		} else {
			statnode.setValue(new Value("not connected"));
		}
		node.removeChild("disable");
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			public void handle(ActionResult event) {
				enable(true);
			}
		});
		node.createChild("enable").setAction(act).build().setSerializable(false);
		if (node.getChildren() == null)
			return;
		for (Node child : node.getChildren().values()) {
			if (child.getAction() == null && child != statnode) {
				String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(Calendar.getInstance().getTime());
				child.setConfig("disconnectedTs", new Value(timeStamp));
			}
		}
	}

	private void scheduleRetry() {
		ScheduledThreadPoolExecutor reconnectStpe = Objects.getDaemonThreadPool();
		reconnectFuture = reconnectStpe.schedule(new Runnable() {

			@Override
			public void run() {
				Value stat = statnode.getValue();
				if (stat == null || !"enabled".equals(stat.getString())) {
					enable(false);
				}
			}

		}, retryDelay, TimeUnit.SECONDS);
		if (retryDelay < 60)
			retryDelay += 2;
	}

	@Override
	protected void remove() {
		super.remove();

		conn.deviceNodes.remove(this);
	}

	private void makeEditAction() {
		Action act = new Action(Permission.READ, new EditHandler());
		act.addParameter(new Parameter("name", ValueType.STRING, new Value(node.getName())));
		act.addParameter(new Parameter("MAC address", ValueType.STRING, node.getAttribute("MAC address")));
		act.addParameter(new Parameter("instance number", ValueType.NUMBER, node.getAttribute("instance number")));
		act.addParameter(new Parameter("network number", ValueType.NUMBER, node.getAttribute("network number")));
		act.addParameter(new Parameter("link service MAC", ValueType.STRING, node.getAttribute("link service MAC")));
		double defint = node.getAttribute("polling interval").getNumber().doubleValue() / 1000;
		act.addParameter(new Parameter("polling interval", ValueType.NUMBER, new Value(defint)));
		act.addParameter(new Parameter("cov usage", ValueType.makeEnum("NONE", "UNCONFIRMED", "CONFIRMED"),
				node.getAttribute("cov usage")));
		act.addParameter(new Parameter("cov lease time (minutes)", ValueType.NUMBER,
				node.getAttribute("cov lease time (minutes)")));
		Node anode = node.getChild("edit");
		if (anode == null)
			node.createChild("edit").setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);
	}

	private class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			long interv = (long) (1000
					* event.getParameter("polling interval", ValueType.NUMBER).getNumber().doubleValue());
			CovType covtype = CovType.NONE;
			try {
				covtype = CovType.valueOf(event.getParameter("cov usage").getString());
			} catch (Exception e1) {
			}
			int covlife = event.getParameter("cov lease time (minutes)", ValueType.NUMBER).getNumber().intValue();
			String mac = event.getParameter("MAC address", ValueType.STRING).getString();
			int instNum = event.getParameter("instance number", new Value(-1)).getNumber().intValue();
			int netNum = event.getParameter("network number", ValueType.NUMBER).getNumber().intValue();
			String linkMac = event.getParameter("link service MAC", new Value("")).getString();
			if (!mac.equals(node.getAttribute("MAC address").getString())
					|| !linkMac.equals(node.getAttribute("link service MAC").getString())
					|| netNum != node.getAttribute("network number").getNumber().intValue()
					|| instNum != node.getAttribute("instance number").getNumber().intValue()) {

				final RemoteDevice d = conn.getDevice(mac, instNum, netNum, linkMac, interv, covtype, covlife);
				conn.getDeviceProps(d);
				device = d;
			}
			interval = interv;
			covType = covtype;
			// try {
			// mac = d.getAddress().getMacAddress().toIpPortString();
			// } catch (Exception e) {
			// mac =
			// Byte.toString(d.getAddress().getMacAddress().getMstpAddress());
			// }
			node.setAttribute("MAC address", new Value(mac));
			node.setAttribute("instance number", new Value(instNum));
			node.setAttribute("network number", new Value(netNum));
			node.setAttribute("polling interval", new Value(interval));
			node.setAttribute("cov usage", new Value(covtype.toString()));
			node.setAttribute("cov lease time (minutes)", new Value(covlife));

			if (!name.equals(node.getName())) {
				rename(name);
			}

			stopPolling();
			startPolling();

			makeEditAction();
		}
	}

	private void makeAlarmActions() {
		Action act = new Action(Permission.READ, new AlarmSummaryHandler());
		act.addResult(new Parameter("Object", ValueType.STRING));
		act.addResult(new Parameter("Alarm State", ValueType.STRING));
		act.addResult(new Parameter("Acked Transitions: To-Offnormal", ValueType.BOOL));
		act.addResult(new Parameter("Acked Transitions: To-Fault", ValueType.BOOL));
		act.addResult(new Parameter("Acked Transitions: To-Normal", ValueType.BOOL));
		act.setResultType(ResultType.TABLE);
		node.createChild("get alarm summary").setAction(act).build().setSerializable(false);

		act = new Action(Permission.READ, new EventInfoHandler());
		act.addResult(new Parameter("Object", ValueType.STRING));
		act.addResult(new Parameter("Notify Type", ValueType.STRING));
		act.addResult(new Parameter("Event State", ValueType.STRING));
		act.addResult(new Parameter("Acked Transitions: To-Offnormal", ValueType.BOOL));
		act.addResult(new Parameter("Acked Transitions: To-Fault", ValueType.BOOL));
		act.addResult(new Parameter("Acked Transitions: To-Normal", ValueType.BOOL));
		act.addResult(new Parameter("Event Enable: To-Offnormal", ValueType.BOOL));
		act.addResult(new Parameter("Event Enable: To-Fault", ValueType.BOOL));
		act.addResult(new Parameter("Event Enable: To-Normal", ValueType.BOOL));
		act.addResult(new Parameter("Event Priority: To-Offnormal", ValueType.BOOL));
		act.addResult(new Parameter("Event Priority: To-Fault", ValueType.BOOL));
		act.addResult(new Parameter("Event Priority: To-Normal", ValueType.BOOL));
		act.addResult(new Parameter("Event Timestamp: To-Offnormal", ValueType.BOOL));
		act.addResult(new Parameter("Event Timestamp: To-Fault", ValueType.BOOL));
		act.addResult(new Parameter("Event Timestamp: To-Normal", ValueType.BOOL));
		act.setResultType(ResultType.TABLE);
		node.createChild("get event information").setAction(act).build().setSerializable(false);

		act = new Action(Permission.READ, new AckAlarmHandler());
		act.addParameter(new Parameter("Event Object Type", ValueType.makeEnum(Utils.enumeratedObjectTypeNames())));
		act.addParameter(new Parameter("Event Object Instance Number", ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter("Event State",
				ValueType.makeEnum("normal", "fault", "offnormal", "highLimit", "lowLimit", "lifeSafetyAlarm")));
		act.addParameter(new Parameter("Event Timestamp", ValueType.STRING));
		act.addParameter(new Parameter("Acknowledging Process Identifier", ValueType.NUMBER));
		act.addParameter(new Parameter("Acknowledgment Source", ValueType.STRING));
		node.createChild("acknowledge alarm").setAction(act).build().setSerializable(false);
	}

	private class AckAlarmHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			ObjectType ot = Utils.parseObjectType(event.getParameter("Event Object Type").getString());
			int inum = event.getParameter("Event Object Instance Number", ValueType.NUMBER).getNumber().intValue();
			ObjectIdentifier oid = new ObjectIdentifier(ot, inum);
			EventState estate = Utils.eventStateFromString(event.getParameter("Event State").getString());
			TimeStamp ets = Utils
					.timestampFromString(event.getParameter("Event Timestamp", ValueType.STRING).getString());
			UnsignedInteger ackid = new UnsignedInteger(
					event.getParameter("Acknowledging Process Identifier", ValueType.NUMBER).getNumber().intValue());
			CharacterString acksrc = new CharacterString(
					event.getParameter("Acknowledgment Source", ValueType.STRING).getString());
			TimeStamp ts = new TimeStamp(new DateTime());

			conn.localDevice.send(device, new AcknowledgeAlarmRequest(ackid, oid, estate, ets, acksrc, ts));
		}
	}

	private class EventInfoHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			try {
				ServiceFuture resp = conn.localDevice.send(device, new GetEventInformationRequest(null));
				GetEventInformationAck ack = (GetEventInformationAck) resp.get();
				Table table = event.getTable();
				for (EventSummary summ : ack.getListOfEventSummaries()) {
					ObjectIdentifier oid = summ.getObjectIdentifier();
					NotifyType ntype = summ.getNotifyType();
					EventState estate = summ.getEventState();
					EventTransitionBits acktrans = summ.getAcknowledgedTransitions();
					EventTransitionBits eenable = summ.getEventEnable();
					UnsignedInteger onprio = summ.getEventPriorities().get(1);
					UnsignedInteger fprio = summ.getEventPriorities().get(2);
					UnsignedInteger nprio = summ.getEventPriorities().get(3);
					TimeStamp onts = summ.getEventTimeStamps().get(1);
					TimeStamp fts = summ.getEventTimeStamps().get(2);
					TimeStamp nts = summ.getEventTimeStamps().get(3);

					Row row = Row.make(new Value(oid.toString()), new Value(ntype.toString()),
							new Value(estate.toString()), new Value(acktrans.isToOffnormal()),
							new Value(acktrans.isToFault()), new Value(acktrans.isToNormal()),
							new Value(eenable.isToOffnormal()), new Value(eenable.isToFault()),
							new Value(eenable.isToNormal()), new Value(onprio.intValue()), new Value(fprio.intValue()),
							new Value(nprio.intValue()), new Value(Utils.timestampToString(onts)),
							new Value(Utils.timestampToString(fts)), new Value(Utils.timestampToString(nts)));
					table.addRow(row);
				}
			} catch (BACnetException e) {
				LOGGER.debug("", e);
			}
		}
	}

	private class AlarmSummaryHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			try {
				// LOGGER.info("getting alarm summary...");
				ServiceFuture resp = conn.localDevice.send(device, new GetAlarmSummaryRequest());
				GetAlarmSummaryAck ack = (GetAlarmSummaryAck) resp.get();
				// LOGGER.info("got alarm summary:");
				// LOGGER.info(ack.getValues().toString());
				Table table = event.getTable();
				for (AlarmSummary summ : ack.getValues()) {
					// LOGGER.info(summ.toString());
					ObjectIdentifier oid = summ.getObjectIdentifier();
					EventState astate = summ.getAlarmState();
					EventTransitionBits acktrans = summ.getAcknowledgedTransitions();
					Row row = Row.make(new Value(oid.toString()), new Value(astate.toString()),
							new Value(acktrans.isToOffnormal()), new Value(acktrans.isToFault()),
							new Value(acktrans.isToNormal()));
					table.addRow(row);
				}
			} catch (BACnetException e) {
				LOGGER.debug("", e);
			}
		}

	}

	@Override
	protected void duplicate(String name) {
		JsonObject jobj = conn.link.copySerializer.serialize();
		JsonObject parentobj = getParentJson(jobj, node);
		JsonObject nodeobj = parentobj.get(node.getName());
		parentobj.put(name, nodeobj);
		conn.link.copyDeserializer.deserialize(jobj);
		Node newnode = node.getParent().getChild(name);
		conn.restoreDevice(newnode);
		return;

	}

	protected JsonObject getParentJson(JsonObject jobj, Node n) {
		return jobj.get(conn.node.getName());
	}

	// polling
	void addPointSub(BacnetPoint point) {
		if (subscribedPoints.containsKey(point.oid))
			return;
		subscribedPoints.put(point.oid, point);
		if (pollingFuture == null)
			startPolling();
	}

	void removePointSub(BacnetPoint point) {
		subscribedPoints.remove(point.oid);
		if (subscribedPoints.size() == 0)
			stopPolling();
	}

	private void stopPolling() {
		if (pollingFuture != null) {
			LOGGER.debug("stopping polling for device " + node.getName());
			pollingFuture.cancel(false);
			pollingFuture = null;
		}
	}

	private void startPolling() {
		if (!enabled || subscribedPoints.size() == 0)
			return;

		LOGGER.debug("starting polling for device " + node.getName());
		pollingFuture = deviceStpe.scheduleWithFixedDelay(new Runnable() {
			public void run() {
				if (conn.localDevice == null) {
					conn.stop();
					return;
				}

				PropertyReferences refs = new PropertyReferences();
				for (ObjectIdentifier oid : subscribedPoints.keySet()) {
					DeviceFolder.addPropertyReferences(refs, oid);
				}
				LOGGER.debug("polling for device " + node.getName());
				getProperties(refs, new ConcurrentHashMap<ObjectIdentifier, BacnetPoint>(subscribedPoints));
			}
		}, 0, interval, TimeUnit.MILLISECONDS);

	}

}
