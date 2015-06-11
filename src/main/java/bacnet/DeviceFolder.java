package bacnet;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.vertx.java.core.Handler;

import bacnet.BacnetConn.CovType;

import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.event.DeviceEventAdapter;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.service.confirmed.SubscribeCOVRequest;
import com.serotonin.bacnet4j.type.AmbiguousValue;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.constructed.DeviceObjectPropertyReference;
import com.serotonin.bacnet4j.type.constructed.PropertyValue;
import com.serotonin.bacnet4j.type.constructed.SequenceOf;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.enumerated.EngineeringUnits;
import com.serotonin.bacnet4j.type.primitive.CharacterString;
import com.serotonin.bacnet4j.type.primitive.Enumerated;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.OctetString;
import com.serotonin.bacnet4j.type.primitive.Primitive;
import com.serotonin.bacnet4j.type.primitive.Real;
import com.serotonin.bacnet4j.type.primitive.SignedInteger;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import com.serotonin.bacnet4j.util.PropertyReferences;
import com.serotonin.bacnet4j.util.PropertyValues;
import com.serotonin.bacnet4j.util.RequestListener;
import com.serotonin.bacnet4j.util.RequestUtils;

public class DeviceFolder {
	protected Node node;
	protected BacnetConn conn;
	protected DeviceNode root;
	//private HashMap<ObjectIdentifier, BacnetPoint> points;
	
	DeviceFolder(BacnetConn conn, Node node) {
		this.conn = conn;
		this.node = node;
		//this.points = new HashMap<ObjectIdentifier, BacnetPoint>();
		node.setAttribute("restore type", new Value("folder"));
		
		Action act = new Action(Permission.READ, new RemoveHandler());
		node.createChild("remove").setAction(act).build().setSerializable(false);
		
		act = new Action(Permission.READ, new AddFolderHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		node.createChild("add folder").setAction(act).build().setSerializable(false);
		
		act = new Action(Permission.READ, new ObjectDiscoveryHandler());
		node.createChild("discover objects").setAction(act).build().setSerializable(false);
		
		act = new Action(Permission.READ, new AddObjectHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		act.addParameter(new Parameter("object type", ValueType.makeEnum("Analog Input", "Analog Output", "Analog Value", "Binary Input", "Binary Output", "Binary Value", "Calendar", "Command", "Device", "Event Enrollment", "File", "Group", "Loop", "Multi-state Input", "Multi-state Output", "Notification Class", "Program", "Schedule", "Averaging", "Multi-state Value", "Trend Log", "Life Safety Point", "Life Safety Zone", "Accumulator", "Pulse Converter", "Event Log", "Trend Log Multiple", "Load Control", "Structured View", "Access Door")));
		act.addParameter(new Parameter("object instance number", ValueType.NUMBER, new Value(0)));
		act.addParameter(new Parameter("use COV", ValueType.BOOL, new Value(false)));
		node.createChild("add object").setAction(act).build().setSerializable(false);
	}
	
	DeviceFolder(BacnetConn conn, Node node, DeviceNode root) {
		this(conn, node);
		this.root = root;
	}
	
	public void restoreLastSession() {
		if (node.getChildren() == null) return;
		for (Node child: node.getChildren().values()) {
			Value restype = child.getAttribute("restore type");
			if (restype != null && restype.getString().equals("folder")) {
				DeviceFolder df = new DeviceFolder(conn, child, root);
				df.restoreLastSession();
			} else if (restype != null && restype.getString().equals("point")) {
				Value ot = child.getAttribute("object type");
		    	Value inum = child.getAttribute("object instance number");
		    	Value cov = child.getAttribute("use COV");
		    	if (ot!=null && inum!=null && cov!=null) {
		    		BacnetPoint bp = new BacnetPoint(this, node, child);
		    		conn.link.setupPoint(bp, this);
		    	} else {
		    		node.removeChild(child);
		    	}
			} else if (child.getAction() == null) {
				node.removeChild(child);
			}
		}
	}
	
	private class AddObjectHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			ObjectType ot = parseObjectType(event.getParameter("object type").getString());
			int instNum = event.getParameter("object instance number", ValueType.NUMBER).getNumber().intValue();
			boolean cov = event.getParameter("use COV", ValueType.BOOL).getBool();
			
			Node pnode = node.createChild(name).build();
			pnode.setAttribute("object type", new Value(ot.toString()));
			pnode.setAttribute("object instance number", new Value(instNum));
			pnode.setAttribute("use COV", new Value(cov));
			pnode.setAttribute("restore type", new Value("point"));
			
			BacnetPoint pt = new BacnetPoint(getMe(), node, pnode);
			conn.link.setupPoint(pt, getMe());
		}
	}
	
	protected class ObjectDiscoveryHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			final PropertyReferences refs = new PropertyReferences();
			final Map<ObjectIdentifier, BacnetPoint> points = new HashMap<ObjectIdentifier, BacnetPoint>();
			try {
				RequestUtils.sendReadPropertyAllowNull(root.conn.localDevice, root.device, root.device.getObjectIdentifier(), PropertyIdentifier.objectList, null, new RequestListener() {
					public boolean requestProgress(double prog, ObjectIdentifier oidin, PropertyIdentifier pid,UnsignedInteger pin, Encodable value) {
                        if (pin == null) {
                        	for (Object o : (SequenceOf<?>) value) {
                        		ObjectIdentifier oid = (ObjectIdentifier) o;
                        		addObjectPoint(oid, refs, points);
                        	}
                        } else {
                        	ObjectIdentifier oid = (ObjectIdentifier) value;
                        	addObjectPoint(oid, refs, points);
                        }
                        return false;
					}
				});
			} catch (BACnetException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			getProperties(refs, points);
		}
	}
	
	void setupCov(BacnetPoint point, DeviceEventAdapter listener) {
		CovType ct = CovType.NONE;
		try {
			ct = CovType.valueOf(root.node.getAttribute("cov usage").getString());
		} catch (Exception e) {
		}
		if (ct == CovType.NONE) return;
		com.serotonin.bacnet4j.type.primitive.Boolean confirmed = new com.serotonin.bacnet4j.type.primitive.Boolean(ct == CovType.CONFIRMED);
		UnsignedInteger lifetime =  new UnsignedInteger(60 * root.node.getAttribute("cov lease time (minutes)").getNumber().intValue());
		UnsignedInteger id = new UnsignedInteger(point.id);
		conn.localDevice.getEventHandler().addListener(listener);
		try {
			conn.localDevice.send(root.device, new SubscribeCOVRequest(id, point.oid, confirmed, lifetime));
		} catch (BACnetException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public CovListener getNewCovListener(BacnetPoint p) {
		return new CovListener(p);
	}
	
	private class CovListener extends DeviceEventAdapter {
		
		BacnetPoint point;
		
		CovListener(BacnetPoint p) {
			this.point = p;
		}
		
		@Override
		public void covNotificationReceived(final UnsignedInteger subscriberProcessIdentifier,
	            final RemoteDevice initiatingDevice, final ObjectIdentifier monitoredObjectIdentifier,
	            final UnsignedInteger timeRemaining, final SequenceOf<PropertyValue> listOfValues) {
			for (PropertyValue pv: listOfValues) {
				updatePointValue(point, pv.getPropertyIdentifier(), pv.getValue());
			}
		}
	}
	
	void getProperties(PropertyReferences refs, final Map<ObjectIdentifier, BacnetPoint> points) {
		try {
			RequestUtils.readProperties(root.conn.localDevice, root.device, refs, new RequestListener() {
			        
			        public boolean requestProgress(double prog, ObjectIdentifier oid, PropertyIdentifier pid, UnsignedInteger unsignedinteger, Encodable encodable) {
			        	BacnetPoint pt = points.get(oid);
			        	
			        	updatePointValue(pt, pid, encodable);
			        	
			        	return false;
			        }
			 });
		} catch (BACnetException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	private void updatePointValue(BacnetPoint pt, PropertyIdentifier pid, Encodable encodable) {
		if (pid.equals(PropertyIdentifier.objectName)) {
            pt.setObjectName(PropertyValues.getString(encodable));
    	} else if (pid.equals(PropertyIdentifier.presentValue) && ObjectType.schedule.intValue() == pt.getObjectTypeId()) {
            handleAmbiguous((AmbiguousValue) encodable, pt, pid);
    	} else if (pid.equals(PropertyIdentifier.presentValue)) {
            pt.setPresentValue(PropertyValues.getString(encodable), pid);
    	} else if (pid.equals(PropertyIdentifier.modelName)) {
            pt.setPresentValue(PropertyValues.getString(encodable), pid);
    	} else if (pid.equals(PropertyIdentifier.units)) {
            String eu = ("engUnit.abbr." + ((EngineeringUnits) encodable).intValue());
            pt.setEngineeringUnits(eu);
            pt.getUnitsDescription().add(PropertyValues.getString(encodable));
        } else if (pid.equals(PropertyIdentifier.inactiveText)) {
            Encodable e = PropertyValues.getNullOnError(encodable);
            String s = "0";
            if (e != null && !StringUtils.isEmpty(e.toString()))
                s = e.toString();
            pt.getUnitsDescription().set(0, s);
        } else if (pid.equals(PropertyIdentifier.activeText)) {
            Encodable e = PropertyValues.getNullOnError(encodable);
            String s = "1";
            if (e != null && !StringUtils.isEmpty(e.toString()))
                s = e.toString();
            pt.getUnitsDescription().set(1, s);
        } else if (pid.equals(PropertyIdentifier.outputUnits)) {
            String eu = ("engUnit.abbr." + ((EngineeringUnits) encodable).intValue());
            pt.setEngineeringUnits(eu);
            pt.getUnitsDescription().add(PropertyValues.getString(encodable));
        } else if (pid.equals(PropertyIdentifier.stateText)) {
            @SuppressWarnings("unchecked")
			SequenceOf<CharacterString> states = (SequenceOf<CharacterString>) encodable;
            for (CharacterString state : states)
                pt.getUnitsDescription().add(state.toString());
        } else if (pid.equals(PropertyIdentifier.modelName)) {
            pt.setPresentValue(PropertyValues.getString(encodable), pid);
        } else if (pid.equals(PropertyIdentifier.logDeviceObjectProperty) && encodable instanceof DeviceObjectPropertyReference) {
            DeviceObjectPropertyReference ref = (DeviceObjectPropertyReference) encodable;
            if (ref.getDeviceIdentifier() != null) {
                pt.setReferenceDeviceId(ref.getDeviceIdentifier().getInstanceNumber());
            } else {
                pt.setReferenceDeviceId(root.device.getInstanceNumber());
                pt.setReferenceObjectTypeId(ref.getObjectIdentifier().getObjectType().intValue());
                pt.setReferenceObjectTypeDescription(ref.getObjectIdentifier().getObjectType().toString());
                pt.setReferenceInstanceNumber(ref.getObjectIdentifier().getInstanceNumber());
                pt.setDataType(getDataType(ref.getObjectIdentifier().getObjectType()));
            }
        } else if (pid.equals(PropertyIdentifier.recordCount)) {
            pt.setPresentValue(PropertyValues.getString(encodable), pid);
        }
	}
	
	void addObjectPoint(ObjectIdentifier oid, PropertyReferences refs, Map<ObjectIdentifier, BacnetPoint> points) {
        addPropertyReferences(refs, oid);

        BacnetPoint pt = new BacnetPoint(this, node, oid);

        points.put(oid, pt);
    }
	
	public static enum DataType {BINARY, MULTISTATE, NUMERIC, ALPHANUMERIC}
	
    public static DataType getDataType(ObjectType objectType) {
        if (isOneOf(objectType, ObjectType.binaryInput, ObjectType.binaryOutput,
                ObjectType.binaryValue))
            return DataType.BINARY;
        else if (isOneOf(objectType, ObjectType.multiStateInput, ObjectType.multiStateOutput,
                ObjectType.multiStateValue, ObjectType.lifeSafetyPoint, ObjectType.lifeSafetyZone))
            return DataType.MULTISTATE;
        else
            return DataType.NUMERIC;
    }
    
    public static boolean isOneOf(int objectTypeId, ObjectType... types) {
        for (ObjectType type : types) {
            if (type.intValue() == objectTypeId)
                return true;
        }
        return false;
    }
	
    static void addPropertyReferences(PropertyReferences refs, ObjectIdentifier oid) {
        refs.add(oid, PropertyIdentifier.objectName);

        ObjectType type = oid.getObjectType();
        if (isOneOf(type, ObjectType.accumulator)) {
            refs.add(oid, PropertyIdentifier.units);
            refs.add(oid, PropertyIdentifier.presentValue);
        }
        else if (isOneOf(type, ObjectType.analogInput, ObjectType.analogOutput,
                ObjectType.analogValue, ObjectType.pulseConverter)) {
            refs.add(oid, PropertyIdentifier.units);
            refs.add(oid, PropertyIdentifier.presentValue);
        }
        else if (isOneOf(type, ObjectType.binaryInput, ObjectType.binaryOutput, ObjectType.binaryValue)) {
            refs.add(oid, PropertyIdentifier.inactiveText);
            refs.add(oid, PropertyIdentifier.activeText);
            refs.add(oid, PropertyIdentifier.presentValue);
        }
        else if (isOneOf(type, ObjectType.device)) {
            refs.add(oid, PropertyIdentifier.modelName);
        }
        else if (isOneOf(type, ObjectType.lifeSafetyPoint)) {
            refs.add(oid, PropertyIdentifier.units);
            refs.add(oid, PropertyIdentifier.presentValue);
        }
        else if (isOneOf(type, ObjectType.loop)) {
            refs.add(oid, PropertyIdentifier.outputUnits);
            refs.add(oid, PropertyIdentifier.presentValue);
        }
        else if (isOneOf(type, ObjectType.multiStateInput, ObjectType.multiStateOutput,
                ObjectType.multiStateValue)) {
            refs.add(oid, PropertyIdentifier.stateText);
            refs.add(oid, PropertyIdentifier.presentValue);
        }
        else if (isOneOf(type, ObjectType.schedule)) {
            refs.add(oid, PropertyIdentifier.presentValue);
        }
        else if (isOneOf(type, ObjectType.trendLog)) {
            refs.add(oid, PropertyIdentifier.logDeviceObjectProperty);
            refs.add(oid, PropertyIdentifier.recordCount);
        }
    }
    
    void handleAmbiguous(AmbiguousValue av, BacnetPoint pt, PropertyIdentifier pid) {
        Primitive primitive;
        try {
            primitive = av.convertTo(Primitive.class);
        }
        catch (BACnetException e) {
            pt.setPresentValue(e.getMessage(), pid);
            return;
        }
        pt.setPresentValue(PropertyValues.getString(primitive), pid);

        if (primitive instanceof com.serotonin.bacnet4j.type.primitive.Boolean)
            pt.setDataType(DataType.BINARY);
        else if (primitive instanceof SignedInteger || primitive instanceof Real || primitive instanceof com.serotonin.bacnet4j.type.primitive.Double)
            pt.setDataType(DataType.NUMERIC);
        else if (primitive instanceof OctetString || primitive instanceof CharacterString)
            pt.setDataType(DataType.ALPHANUMERIC);
        else if (primitive instanceof Enumerated || primitive instanceof UnsignedInteger)
            pt.setDataType(DataType.MULTISTATE);
    }
    
    public static boolean isOneOf(ObjectType objectType, ObjectType... types) {
        return isOneOf(objectType.intValue(), types);
    }
	
	protected class RemoveHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			remove();
		}
	}
	
	protected void remove() {
		node.clearChildren();
		node.getParent().removeChild(node);
	}
	
	protected class AddFolderHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			Node child = node.createChild(name).build();
			new DeviceFolder(conn, child, root);
		}
	}
	
	static ObjectType parseObjectType(String otStr) {
		switch (otStr) {
		case "Analog Input": return ObjectType.analogInput;
		case "Analog Output": return ObjectType.analogOutput;
		case "Analog Value": return ObjectType.analogValue;
		case "Binary Input": return ObjectType.binaryInput;
		case "Binary Output": return ObjectType.binaryOutput;
		case "Binary Value": return ObjectType.binaryValue;
		case "Calendar": return ObjectType.calendar;
		case "Command": return ObjectType.command;
		case "Device": return ObjectType.device;
		case "Event Enrollment": return ObjectType.eventEnrollment;
		case "File": return ObjectType.file;
		case "Group": return ObjectType.group;
		case "Loop": return ObjectType.loop;
		case "Multi-state Input": return ObjectType.multiStateInput;
		case "Multi-state Output": return ObjectType.multiStateOutput;
		case "Notification Class": return ObjectType.notificationClass;
		case "Program": return ObjectType.program;
		case "Schedule": return ObjectType.schedule;
		case "Averaging": return ObjectType.averaging;
		case "Multi-state Value": return ObjectType.multiStateValue;
		case "Trend Log": return ObjectType.trendLog;
		case "Life Safety Point": return ObjectType.lifeSafetyPoint;
		case "Life Safety Zone": return ObjectType.lifeSafetyZone;
		case "Accumulator": return ObjectType.accumulator;
		case "Pulse Converter": return ObjectType.pulseConverter;
		case "Event Log": return ObjectType.eventLog;
		case "Trend Log Multiple": return ObjectType.trendLogMultiple;
		case "Load Control": return ObjectType.loadControl;
		case "Structured View": return ObjectType.structuredView;
		case "Access Door": return ObjectType.accessDoor;
		default: return null;
		}
	}
	
	public DeviceFolder getMe() {
		return this;
	}
	

}
