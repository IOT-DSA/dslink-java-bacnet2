package bacnet;

import java.util.ArrayList;
import java.util.List;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.vertx.java.core.Handler;

import com.serotonin.bacnet4j.event.DeviceEventAdapter;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.obj.ObjectProperties;
import com.serotonin.bacnet4j.service.confirmed.WritePropertyRequest;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.enumerated.BinaryPV;
import com.serotonin.bacnet4j.type.enumerated.LifeSafetyState;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.Real;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;

import bacnet.DeviceFolder.DataType;

public class BacnetPoint {
	
	private static PointCounter numPoints = new PointCounter();
	
	private DeviceFolder folder;
	private Node parent;
	Node node;
	ObjectIdentifier oid;
	private PropertyIdentifier pid;
	int id;
	private DeviceEventAdapter listener;
	
	private int objectTypeId;
    private int instanceNumber;
    private String objectTypeDescription;
    private String objectName;
    private String presentValue;
    private boolean cov;
    private String engineeringUnits;

    // Default values for points
    private DataType dataType;
    private List<String> unitsDescription = new ArrayList<String>();
    private int referenceDeviceId;
    private int referenceObjectTypeId;
    private String referenceObjectTypeDescription;
    private int referenceInstanceNumber;
	
    public BacnetPoint(DeviceFolder folder, Node parent, ObjectIdentifier oid) {
    	this.folder = folder;
    	this.parent = parent;
    	this.node = null;
    	this.oid = oid;
    	id = numPoints.increment();
    }
    
    public int getObjectTypeId() {
        return objectTypeId;
    }

    public void setObjectTypeId(int objectTypeId) {
        this.objectTypeId = objectTypeId;
        if (node != null) {
        	Node vnode = node.getChild("objectTypeId");
        	if (vnode != null) vnode.setValue(new Value(objectTypeId));
        	else node.createChild("objectTypeId").setValueType(ValueType.NUMBER).setValue(new Value(objectTypeId)).build();
        	System.out.println("objectTypeID updated to " + objectTypeId);
        }
    }

    public int getInstanceNumber() {
        return instanceNumber;
    }
    
    public void setInstanceNumber(int instanceNumber) {
        this.instanceNumber = instanceNumber;
        if (node != null) {
        	Node vnode = node.getChild("instanceNumber");
        	if (vnode != null) vnode.setValue(new Value(instanceNumber));
        	else node.createChild("instanceNumber").setValueType(ValueType.NUMBER).setValue(new Value(instanceNumber)).build();
        	System.out.println("instanceNumber updated to " + instanceNumber);
        }
    }

    public String getObjectTypeDescription() {
        return objectTypeDescription;
    }

    public void setObjectTypeDescription(String objectTypeDescription) {
        this.objectTypeDescription = objectTypeDescription;
        if (node != null && objectTypeDescription != null) {
        	Node vnode = node.getChild("objectTypeDescription");
        	if (vnode != null) vnode.setValue(new Value(objectTypeDescription));
        	else node.createChild("objectTypeDescription").setValueType(ValueType.STRING).setValue(new Value(objectTypeDescription)).build();
        	System.out.println("objectTypeDescription updated to " + objectTypeDescription);
        }
    }

    public String getObjectName() {
        return objectName;
    }

    public void setObjectName(String objectName) {
    	if (this.objectName != null) {
    		if (!this.objectName.equals(objectName)) parent.removeChild(this.objectName);
    		else return;
    	}
        this.objectName = objectName;
        setupNode();
        
    }
    
    private void setupNode() {
    	if (node == null) {
    		node = parent.createChild(objectName).build();
    		node.setSerializable(false);
    	}
        setObjectTypeId(objectTypeId);
        setInstanceNumber(instanceNumber);
        setObjectTypeDescription(objectTypeDescription);
        if (presentValue != null) setPresentValue(presentValue, pid);
        else node.createChild("presentValue").setValueType(ValueType.STRING).setValue(new Value(" ")).build();
        setCov(cov);
        setEngineeringUnits(engineeringUnits);
        setDataType(dataType);
        setUnitsDescription(unitsDescription);
        setReferenceDeviceId(referenceDeviceId);
        setReferenceObjectTypeId(referenceObjectTypeId);
        setReferenceObjectTypeDescription(referenceObjectTypeDescription);
        setReferenceInstanceNumber(referenceInstanceNumber);
        clearActions();
    	makeActions();
        
        if (listener!=null) folder.conn.localDevice.getEventHandler().removeListener(listener);
        
        listener = folder.conn.link.setupPoint(this, folder.root);
    }
    
    private void makeActions() {
    	Action act = new Action(Permission.READ, new RemoveHandler());
    	node.createChild("remove").setAction(act).build().setSerializable(false);
    	
    	act = new Action(Permission.READ, new EditHandler());
    	act.addParameter(new Parameter("cov", ValueType.BOOL, new Value(cov)));
    	node.createChild("edit").setAction(act).build().setSerializable(false);
    
    }
    
    private void clearActions() {
    	if (node == null || node.getChildren() == null) return;
    	for (Node child: node.getChildren().values()) {
    		if (child.getAction() != null) {
    			node.removeChild(child);
    		}
    	}
    }
    
    private class SetHandler implements Handler<ActionResult> {
    	public void handle(ActionResult event) {
    		String newval = event.getParameter("value", ValueType.STRING).getString();
    		Encodable enc = valueToEncodable(newval, oid.getObjectType(), pid);
    		try {
				folder.conn.localDevice.send(folder.root.device, new WritePropertyRequest(oid, pid, null, enc, new UnsignedInteger(1)));
			} catch (BACnetException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
    	}
    }
    
    private class EditHandler implements Handler<ActionResult> {
    	public void handle(ActionResult event) {
    		cov = event.getParameter("cov", ValueType.BOOL).getBool();
    		setupNode();
    	}
    }
    
    private class RemoveHandler implements Handler<ActionResult> {
    	public void handle(ActionResult event) {
    		node.clearChildren();
    		parent.removeChild(node);
    	}
    }

    public DataType getDataType() {
        return dataType;
    }

    public void setDataType(DataType dataType) {
        this.dataType = dataType;
        if (node != null && dataType != null) {
        	Node vnode = node.getChild("dataType");
        	if (vnode != null) vnode.setValue(new Value(dataType.toString()));
        	else node.createChild("dataType").setValueType(ValueType.STRING).setValue(new Value(dataType.toString())).build();
        	System.out.println("dataType updated to " + dataType);
        }
    }

    public List<String> getUnitsDescription() {
        return unitsDescription;
    }

    public void setUnitsDescription(List<String> unitsDescription) {
        this.unitsDescription = unitsDescription;
        if (node != null) {
        	Node vnode = node.getChild("unitsDescription");
        	if (vnode != null) vnode.setValue(new Value(unitsDescription.toString()));
        	else node.createChild("unitsDescription").setValueType(ValueType.STRING).setValue(new Value(unitsDescription.toString())).build();
        	System.out.println("unitsDescription updated to " + unitsDescription);
        }
    }

    public String getPresentValue() {
        return presentValue;
    }

    public void setPresentValue(String presentValue, PropertyIdentifier pid) {
    	this.pid = pid;
        this.presentValue = presentValue;
        if (node != null && presentValue != null) {
        	Node vnode = node.getChild("presentValue");
        	if (vnode != null) vnode.setValue(new Value(presentValue));
        	else vnode = node.createChild("presentValue").setValueType(ValueType.STRING).setValue(new Value(presentValue)).build();
        	System.out.println("presentValue updated to " + presentValue);
        	
        	vnode.removeChild("set");
        	
        	Action act = new Action(Permission.READ, new SetHandler());
        	act.addParameter(new Parameter("value", ValueType.STRING, new Value(presentValue)));
        	vnode.createChild("set").setAction(act).build().setSerializable(false);
        }
    }

    public boolean isCov() {
        return cov;
    }

    public void setCov(boolean cov) {
        this.cov = cov;
        if (node != null) {
        	Node vnode = node.getChild("cov");
        	if (vnode != null) vnode.setValue(new Value(cov));
        	else node.createChild("cov").setValueType(ValueType.BOOL).setValue(new Value(cov)).build();
        	System.out.println("cov updated to " + cov);
        }
    }

    public String getEngineeringUnits() {
        return engineeringUnits;
    }

    public void setEngineeringUnits(String engineeringUnits) {
        this.engineeringUnits = engineeringUnits;
        if (node != null && engineeringUnits != null) {
        	Node vnode = node.getChild("engineeringUnits");
        	if (vnode != null) vnode.setValue(new Value(engineeringUnits));
        	else node.createChild("engineeringUnits").setValueType(ValueType.STRING).setValue(new Value(engineeringUnits)).build();
        	System.out.println("engineeringUnits updated to " + engineeringUnits);
        }
    }

    public int getReferenceDeviceId() {
        return referenceDeviceId;
    }

    public void setReferenceDeviceId(int referenceDeviceId) {
        this.referenceDeviceId = referenceDeviceId;
        if (node != null) {
        	Node vnode = node.getChild("referenceDeviceId");
        	if (vnode != null) vnode.setValue(new Value(referenceDeviceId));
        	else node.createChild("referenceDeviceId").setValueType(ValueType.NUMBER).setValue(new Value(referenceDeviceId)).build();
        	System.out.println("referenceDeviceId updated to " + referenceDeviceId);
        }
    }

    public int getReferenceObjectTypeId() {
        return referenceObjectTypeId;
    }

    public void setReferenceObjectTypeId(int referenceObjectTypeId) {
        this.referenceObjectTypeId = referenceObjectTypeId;
        if (node != null) {
        	Node vnode = node.getChild("referenceObjectTypeId");
        	if (vnode != null) vnode.setValue(new Value(referenceObjectTypeId));
        	else node.createChild("referenceObjectTypeId").setValueType(ValueType.NUMBER).setValue(new Value(referenceObjectTypeId)).build();
        	System.out.println("referenceObjectTypeId updated to " + referenceObjectTypeId);
        }
    }

    public String getReferenceObjectTypeDescription() {
        return referenceObjectTypeDescription;
    }

    public void setReferenceObjectTypeDescription(String referenceObjectTypeDescription) {
        this.referenceObjectTypeDescription = referenceObjectTypeDescription;
        if (node != null && referenceObjectTypeDescription != null) {
        	Node vnode = node.getChild("referenceObjectTypeDescription");
        	if (vnode != null) vnode.setValue(new Value(referenceObjectTypeDescription));
        	else node.createChild("referenceObjectTypeDescription").setValueType(ValueType.STRING).setValue(new Value(referenceObjectTypeDescription)).build();
        	System.out.println("referenceObjectTypeDescription updated to " + referenceObjectTypeDescription);
        }
    }

    public int getReferenceInstanceNumber() {
        return referenceInstanceNumber;
    }

    public void setReferenceInstanceNumber(int referenceInstanceNumber) {
        this.referenceInstanceNumber = referenceInstanceNumber;
        if (node != null) {
        	Node vnode = node.getChild("referenceInstanceNumber");
        	if (vnode != null) vnode.setValue(new Value(referenceInstanceNumber));
        	else node.createChild("referenceInstanceNumber").setValueType(ValueType.NUMBER).setValue(new Value(referenceInstanceNumber)).build();
        	System.out.println("referenceInstanceNumber updated to " + referenceInstanceNumber);
        }
    }
    
    private Encodable valueToEncodable(String value, ObjectType objectType, PropertyIdentifier pid) {
        Class<? extends Encodable> clazz = ObjectProperties.getPropertyTypeDefinition(objectType, pid).getClazz();
        
        switch (dataType) {
        case BINARY: {
        	boolean b = Boolean.parseBoolean(value) || Integer.parseInt(value) == 1;
            if (clazz == BinaryPV.class) {
                if (b)
                    return BinaryPV.active;
                return BinaryPV.inactive;
            }

            if (clazz == UnsignedInteger.class)
                return new UnsignedInteger(b ? 1 : 0);

            if (clazz == LifeSafetyState.class)
                return new LifeSafetyState(b ? 1 : 0);

            if (clazz == Real.class)
                return new Real(b ? 1 : 0);
        }
        case NUMERIC: {
            double d = Double.parseDouble(value);
            if (clazz == BinaryPV.class) {
                if (d != 0)
                    return BinaryPV.active;
                return BinaryPV.inactive;
            }

            if (clazz == UnsignedInteger.class)
                return new UnsignedInteger((int) d);

            if (clazz == LifeSafetyState.class)
                return new LifeSafetyState((int) d);

            if (clazz == Real.class)
                return new Real((float) d);
        }
//        case ALPHANUMERIC: {
//            String s = value;
//            if (clazz == BinaryPV.class) {
//                if (Boolean.parseBoolean(value) || Integer.parseInt(value) == 1)
//                    return BinaryPV.active;
//                return BinaryPV.inactive;
//            }
//
//            if (clazz == UnsignedInteger.class)
//                return new UnsignedInteger(MultistateValue.parseMultistate(s).getIntegerValue());
//
//            if (clazz == LifeSafetyState.class)
//                return new LifeSafetyState(MultistateValue.parseMultistate(s).getIntegerValue());
//
//            if (clazz == Real.class)
//                return new Real(NumericValue.parseNumeric(s).getFloatValue());
//        }
//        case MULTISTATE: {
//        	int i = ((MultistateValue) value).getIntegerValue();
//            if (clazz == BinaryPV.class) {
//                if (i != 0)
//                    return BinaryPV.active;
//                return BinaryPV.inactive;
//            }
//
//            if (clazz == UnsignedInteger.class)
//                return new UnsignedInteger(i);
//
//            if (clazz == LifeSafetyState.class)
//                return new LifeSafetyState(i);
//
//            if (clazz == Real.class)
//                return new Real(i);
//        }
        default: return BinaryPV.inactive;
        }

	}
    
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + instanceNumber;
        result = prime * result + objectTypeId;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        BacnetPoint other = (BacnetPoint) obj;
        if (instanceNumber != other.instanceNumber)
            return false;
        if (objectTypeId != other.objectTypeId)
            return false;
        return true;
    }

    private static class PointCounter {
    	private int count = 0;
    	int increment() {
    		int r = count;
    		count += 1;
    		return r;
    	}
    }
    
	
}
