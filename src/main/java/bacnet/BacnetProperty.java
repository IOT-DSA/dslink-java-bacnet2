package bacnet;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.util.handler.Handler;

import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;

public class BacnetProperty {
	
	static final String ACTION_REMOVE = "remove";
	
	BacnetDevice device;
	BacnetObject object;
	Node node;
	ObjectIdentifier oid;
	PropertyIdentifier pid;
	
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
	
	
	protected void setup() {
		makeRemoveAction();
		
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
		device.subscribeProperty(this);
	}
	
	protected void unsubscribe() {
		device.unsubscribeProperty(this);
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
	
	private void remove() {
		node.delete(false);
	}

}
