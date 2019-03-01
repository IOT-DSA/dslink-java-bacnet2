package bacnet;

import org.apache.commons.lang3.tuple.Pair;
import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.Writable;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValuePair;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.ServiceFuture;
import com.serotonin.bacnet4j.obj.ObjectProperties;
import com.serotonin.bacnet4j.service.confirmed.WritePropertyRequest;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.Null;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;

public class BacnetProperty {
	 private static final Logger LOGGER = LoggerFactory.getLogger(BacnetProperty.class);

	static final String ACTION_REMOVE = "remove";
	static final String ACTION_EDIT = "edit";
	static final String CONFIG_WRITABLE = "Writable";

	BacnetDevice device;
	BacnetObject object;
	Node node;
	ObjectIdentifier oid;
	PropertyIdentifier pid;
	private boolean covSubscribed = false;

	BacnetProperty(BacnetDevice device, Node node, ObjectIdentifier oid, PropertyIdentifier pid) {
		this.device = device;
		this.node = node;
		this.oid = oid;
		this.pid = pid;
		if (node != null && node.getRoConfig(CONFIG_WRITABLE) == null) {
		    node.setRoConfig(CONFIG_WRITABLE, new Value(shouldBeSettable()));
		}
	}

	BacnetProperty(BacnetDevice device, BacnetObject object, Node node, ObjectIdentifier oid, PropertyIdentifier pid) {
		this(device, node, oid, pid);
		this.object = object;
	}

	protected void updateHeadless() {
		if (object.headlessPolling) {
			node.setShouldPostCachedValue(false);
		} else {
			node.setShouldPostCachedValue(true);
		}
	}

	protected void setup() {
		object.properties.add(this);
		makeRemoveAction();

		updateHeadless();

		node.getListener().setOnSubscribeHandler(new Handler<Node>() {
			@Override
			public void handle(Node event) {
				subscribe();
			}
		});
		node.getListener().setOnUnsubscribeHandler(new Handler<Node>() {
			@Override
			public void handle(Node event) {
				unsubscribe();
			}
		});
		makeSettable();
		makeEditAction();
	}
	
	protected boolean isWritable() {
	    return node.getRoConfig(CONFIG_WRITABLE).getBool();
	}
	
	protected void setWritable(boolean writable) {
	    node.setRoConfig(CONFIG_WRITABLE, new Value(writable));
	    makeSettable();
	}

	private void makeSettable() {
		if (isWritable()) {
			node.setWritable(Writable.WRITE);
			node.getListener().setValueHandler(new Handler<ValuePair>() {
				@Override
				public void handle(ValuePair event) {
					handleSet(event);
				}
			});
		} else {
		    node.setWritable(Writable.NEVER);
		}
	}

	protected boolean shouldBeSettable() {
		if (pid.isOneOf(PropertyIdentifier.valueSource, PropertyIdentifier.globalIdentifier)) {
			return true;
		}
		ObjectType ot = oid.getObjectType();
		if (Utils.isOneOf(ot, ObjectType.averaging)) {
			return pid.isOneOf(PropertyIdentifier.attemptedSamples, PropertyIdentifier.windowInterval,
					PropertyIdentifier.windowSamples);
		}
		if (Utils.isOneOf(ot, ObjectType.device)) {
			return pid.isOneOf(PropertyIdentifier.timeSynchronizationRecipients,
					PropertyIdentifier.utcTimeSynchronizationRecipients, PropertyIdentifier.timeSynchronizationInterval,
					PropertyIdentifier.alignIntervals, PropertyIdentifier.intervalOffset,
					PropertyIdentifier.deployedProfileLocation);
		}
		if (Utils.isOneOf(ot, ObjectType.file)) {
			return pid.isOneOf(PropertyIdentifier.fileSize, PropertyIdentifier.recordCount, PropertyIdentifier.archive);
		}
		if (Utils.isOneOf(ot, ObjectType.lifeSafetyPoint, ObjectType.lifeSafetyZone)) {
			return pid.isOneOf(PropertyIdentifier.trackingValue, PropertyIdentifier.reliability,
					PropertyIdentifier.mode);
		}
		if (Utils.isOneOf(ot, ObjectType.program)) {
			return pid.equals(PropertyIdentifier.programChange);
		}
		if (Utils.isOneOf(ot, ObjectType.pulseConverter, ObjectType.accessZone)) {
			return pid.equals(PropertyIdentifier.adjustValue);
		}
		if (Utils.isOneOf(ot, ObjectType.trendLog, ObjectType.trendLogMultiple, ObjectType.eventLog)) {
			return pid.isOneOf(PropertyIdentifier.enable, PropertyIdentifier.startTime, PropertyIdentifier.stopTime,
					PropertyIdentifier.recordCount, PropertyIdentifier.logInterval);
		}
		if (Utils.isOneOf(ot, ObjectType.loadControl)) {
			return pid.isOneOf(PropertyIdentifier.requestedShedLevel, PropertyIdentifier.startTime,
					PropertyIdentifier.shedDuration, PropertyIdentifier.dutyWindow, PropertyIdentifier.enable,
					PropertyIdentifier.shedLevels);
		}
		if (Utils.isOneOf(ot, ObjectType.networkSecurity)) {
			return pid.isOneOf(PropertyIdentifier.baseDeviceSecurityPolicy,
					PropertyIdentifier.networkAccessSecurityPolicies, PropertyIdentifier.securityTimeWindow,
					PropertyIdentifier.packetReorderTime, PropertyIdentifier.lastKeyServer,
					PropertyIdentifier.securityPduTimeout, PropertyIdentifier.doNotHide);
		}
		if (Utils.isOneOf(ot, ObjectType.globalGroup)) {
			return pid.isOneOf(PropertyIdentifier.covuPeriod, PropertyIdentifier.covuRecipients);
		}
		if (Utils.isOneOf(ot, ObjectType.notificationForwarder)) {
			return pid.equals(PropertyIdentifier.subscribedRecipients);
		}
		if (Utils.isOneOf(ot, ObjectType.channel)) {
			return pid.isOneOf(PropertyIdentifier.listOfObjectPropertyReferences, PropertyIdentifier.channelNumber,
					PropertyIdentifier.controlGroups);
		}
		if (Utils.isOneOf(ot, ObjectType.lightingOutput)) {
			return pid.isOneOf(PropertyIdentifier.lightingCommand, PropertyIdentifier.minActualValue,
					PropertyIdentifier.maxActualValue);
		}
		if (Utils.isOneOf(ot, ObjectType.networkPort)) {
			return pid.isOneOf(PropertyIdentifier.networkNumber, PropertyIdentifier.command,
					PropertyIdentifier.maxMaster, PropertyIdentifier.maxInfoFrames, PropertyIdentifier.slaveProxyEnable,
					PropertyIdentifier.manualSlaveAddressBinding);
		}
		if (Utils.isOneOf(ot, ObjectType.timer)) {
			return pid.isOneOf(PropertyIdentifier.reliability, PropertyIdentifier.timerState,
					PropertyIdentifier.timerRunning);
		}
		if (Utils.isOneOf(ot, ObjectType.accumulator)) {
			return pid.isOneOf(PropertyIdentifier.valueSet, PropertyIdentifier.valueBeforeChange);
		}
		return false;
	}

	private void handleSet(ValuePair event) {
		if (!event.isFromExternalSource()) {
			return;
		}
		Value newval = event.getCurrent();
//		LOGGER.debug("Handling set :" + newval.toString() + " on " + node.getPath());
		write(newval);
	}

	protected void write(Value newval) {
		Encodable enc = encodableFromValue(newval);
		if (enc == null) {
			return;
		}
//		LOGGER.debug("Sending write request : (" + oid.toString() + ", " + pid.toString() + ", null, " + enc.toString() + ", null)");
		sendWriteRequest(new WritePropertyRequest(oid, pid, null, enc, null));
	}

	protected ServiceFuture sendWriteRequest(WritePropertyRequest request) {
		return Utils.sendConfirmedRequest(device.conn, device, request);
	}
	
	protected Encodable encodableFromValue(Value val) {
		if (val == null) {
			return Null.instance;
		} else {
			Class<? extends Encodable> clazz = ObjectProperties
					.getObjectPropertyTypeDefinition(oid.getObjectType(), pid).getPropertyTypeDefinition().getClazz();
			return TypeUtils.formatEncodable(clazz, val, oid.getObjectType(), pid);
		}
	}

	protected void subscribe() {
		if (object.useCov && (PropertyIdentifier.presentValue.equals(pid) || PropertyIdentifier.statusFlags.equals(pid)
				|| (ObjectType.loop.equals(oid.getObjectType()) && (PropertyIdentifier.setpoint.equals(pid)
						|| PropertyIdentifier.controlledVariableValue.equals(pid)))
				|| (ObjectType.pulseConverter.equals(oid.getObjectType())
						&& PropertyIdentifier.updateTime.equals(pid)))) {
			subscribeCov();
		} else {
			device.subscribeProperty(this);
		}
	}

	protected void unsubscribe() {
		unsubscribeCov();
		device.unsubscribeProperty(this);
	}

	void subscribeCov() {
		synchronized (object.lock) {
			if (!covSubscribed) {
				covSubscribed = true;
				object.addCovSub();
			}
		}
	}

	void unsubscribeCov() {
		synchronized (object.lock) {
			if (covSubscribed) {
				covSubscribed = false;
				object.removeCovSub();
			}
		}
	}

	boolean getCovSubscribed() {
		return covSubscribed;
	}

	public void updateValue(Encodable value) {
		Pair<ValueType, Value> vtandv = TypeUtils.parseEncodable(value);
		ValueType vt = vtandv.getLeft();
		Value v = vtandv.getRight();
		node.setValueType(vt);
		node.setValue(v);
	}

	protected void makeRemoveAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
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

	protected void remove() {
		object.properties.remove(this);
		node.delete(false);
	}
	
	protected void makeEditAction() {
	    Action act = new Action(Permission.READ, new Handler<ActionResult>() {
            @Override
            public void handle(ActionResult event) {
                edit(event);
            }
        });
        act.addParameter(new Parameter(CONFIG_WRITABLE, ValueType.BOOL, node.getRoConfig(CONFIG_WRITABLE)));
        Node anode = node.getChild(ACTION_EDIT, true);
        if (anode == null) {
            node.createChild(ACTION_EDIT, true).setAction(act).build().setSerializable(false);
        } else {
            anode.setAction(act);
        }
	}
	
	private void edit(ActionResult event) {
	    boolean newWritable = event.getParameter(CONFIG_WRITABLE, ValueType.BOOL).getBool();
	    setWritable(newWritable);
        makeEditAction();
	}

}
