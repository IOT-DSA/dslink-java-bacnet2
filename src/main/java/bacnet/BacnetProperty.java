package bacnet;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.util.handler.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.util.RequestUtils;

public class BacnetProperty {
	private static final Logger LOGGER = LoggerFactory.getLogger(BacnetProperty.class);
	
	static final String ACTION_REMOVE = "remove";
	
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
	}
	
	protected void subscribe() {
		if (object.useCov 
				&& (PropertyIdentifier.presentValue.equals(pid) 
						|| PropertyIdentifier.statusFlags.equals(pid)
						|| (ObjectType.loop.equals(oid.getObjectType()) 
								&& (PropertyIdentifier.setpoint.equals(pid) 
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
		synchronized(object.lock) {
			if (!covSubscribed) {
				covSubscribed = true;
				object.addCovSub();
			}
		}
	}
	
	void unsubscribeCov() {
		synchronized(object.lock) {
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
		node.setValue(new Value(value.toString()));
	}
	
	protected void makeRemoveAction() {
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
	
	protected void remove() {
		object.properties.remove(this);
		node.delete(false);
	}

}
