package bacnet;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.NodeBuilder;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.Handler;

import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;

public class BacnetLocalDevice {
	
	static final String ACTION_REMOVE = "remove";
	static final String ACTION_ADD_FOLDER = "add folder";
	static final String ACTION_ADD_OBJECT = "add object";
	
	final BacnetConn conn;
	private final Node node;
	
	BacnetLocalDevice(BacnetConn conn, Node node) {
		this.conn = conn;
		this.node = node;
		node.setRoConfig("restoreAs", new Value("folder"));
	}
	
	public void init() {
		restoreFolder(node);
	}
	
	private void restoreFolder(Node fnode) {
		makeFolderActions(fnode);
		if (fnode.getChildren() == null) {
			return;
		}
		for (Node child: fnode.getChildren().values()) {
			Value restype = child.getRoConfig("restoreAs");
			if (restype != null && "folder".equals(restype.getString())) {
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
					BacnetLocalObject bo = new BacnetLocalObject(this, child, oid);
					bo.restoreLastSession();
				} else {
					child.delete(false);
				}
			} else if (child.getAction() == null) {
				child.delete(false);
			}
		}
	}
	
	private void makeFolderActions(Node fnode) {
		makeRemoveAction(fnode);
		makeAddFolderAction(fnode);
		makeAddObjectAction(fnode);
	}
	
	private void makeRemoveAction(final Node fnode) {
		if (fnode == node) {
			return;
		}
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
	
	private void makeAddObjectAction(final Node fnode) {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			@Override
			public void handle(ActionResult event) {
				addObject(fnode, event);
			}
		});
		act.addParameter(new Parameter("Name", ValueType.STRING));
		act.addParameter(new Parameter("Object Type", ValueType.makeEnum(Utils.getObjectTypeList())));
		act.addParameter(new Parameter("Instance Number", ValueType.NUMBER));
		
		Node anode = fnode.getChild(ACTION_ADD_OBJECT, true);
		if (anode == null) {
			fnode.createChild(ACTION_ADD_OBJECT, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	private void addObject(Node fnode, ActionResult event) {
		String name = event.getParameter("Name", ValueType.STRING).getString();
		String otstr = event.getParameter("Object Type").getString();
		int instnum = event.getParameter("Instance Number", ValueType.NUMBER).getNumber().intValue();
		ObjectType type;
		try {
			type = ObjectType.forName(otstr);
		} catch (Exception e) {
			return;
		}
		ObjectIdentifier oid = new ObjectIdentifier(type, instnum);
		NodeBuilder b = fnode.createChild(name, true).setRoConfig("restoreAs", new Value("object"))
				.setRoConfig("Object Type", new Value(oid.getObjectType().toString()))
				.setRoConfig("Instance Number", new Value(oid.getInstanceNumber()))
				.setValueType(ValueType.STRING).setValue(new Value(""));
		BacnetLocalObject bo = new BacnetLocalObject(this, b.getChild(), oid);
		bo.init();
		b.build();
	}
	

}
