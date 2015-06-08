package bacnet;

import java.util.ArrayList;
import java.util.List;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.vertx.java.core.Handler;

import bacnet.DeviceFolder.DataType;

public class BacnetPoint {
	
	private Node parent;
	private Node node;
	
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
	
    public BacnetPoint(Node parent) {
    	this.parent = parent;
    	this.node = null;
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
        }
    }

    public String getObjectName() {
        return objectName;
    }

    public void setObjectName(String objectName) {
    	if (this.objectName != null) parent.removeChild(this.objectName);
        this.objectName = objectName;
        setupNode();
        
    }
    
    private void setupNode() {
    	this.node = parent.createChild(objectName).build();
        node.setSerializable(false);
        makeActions();
        node.createChild("objectTypeId").setValueType(ValueType.NUMBER).setValue(new Value(objectTypeId)).build();
        node.createChild("instanceNumber").setValueType(ValueType.NUMBER).setValue(new Value(instanceNumber)).build();
        if (objectTypeDescription != null) node.createChild("objectTypeDescription").setValueType(ValueType.STRING).setValue(new Value(objectTypeDescription)).build();
        if (presentValue != null) node.createChild("presentValue").setValueType(ValueType.STRING).setValue(new Value(presentValue)).build();
        node.createChild("cov").setValueType(ValueType.BOOL).setValue(new Value(cov)).build();
        if (engineeringUnits != null) node.createChild("engineeringUnits").setValueType(ValueType.STRING).setValue(new Value(engineeringUnits)).build();
    	if (dataType != null) node.createChild("dataType").setValueType(ValueType.STRING).setValue(new Value(dataType.toString())).build();
    	if (unitsDescription != null) node.createChild("unitsDescription").setValueType(ValueType.STRING).setValue(new Value(unitsDescription.toString())).build();
        node.createChild("referenceDeviceId").setValueType(ValueType.NUMBER).setValue(new Value(referenceDeviceId)).build();
        node.createChild("referenceObjectTypeId").setValueType(ValueType.NUMBER).setValue(new Value(referenceObjectTypeId)).build();
        if (referenceObjectTypeDescription != null) node.createChild("referenceObjectTypeDescription").setValueType(ValueType.STRING).setValue(new Value(referenceObjectTypeDescription)).build();
        node.createChild("referenceInstanceNumber").setValueType(ValueType.NUMBER).setValue(new Value(referenceInstanceNumber)).build();
    }
    
    private void makeActions() {
    	Action act = new Action(Permission.READ, new RemoveHandler());
    	node.createChild("remove").setAction(act).build().setSerializable(false);
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
        }
    }

    public String getPresentValue() {
        return presentValue;
    }

    public void setPresentValue(String presentValue) {
        this.presentValue = presentValue;
        if (node != null && presentValue != null) {
        	Node vnode = node.getChild("presentValue");
        	if (vnode != null) vnode.setValue(new Value(presentValue));
        	else node.createChild("presentValue").setValueType(ValueType.STRING).setValue(new Value(presentValue)).build();
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

	
}
