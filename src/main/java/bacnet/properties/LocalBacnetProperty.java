package bacnet.properties;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Writable;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValuePair;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.Handler;
import org.dsa.iot.dslink.util.json.JsonArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.obj.BACnetObject;
import com.serotonin.bacnet4j.obj.BACnetObjectListener;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;

import bacnet.LocalBacnetPoint;
import bacnet.LocalBacnetPoint.SimpleBACnetObjectListener;

public class LocalBacnetProperty {

	private static final Logger LOGGER ;
	static {
		LOGGER = LoggerFactory.getLogger(LocalBacnetProperty.class);
	}
	
	LocalBacnetPoint bacnetPoint;
	BACnetObject bacnetObj;
	private Node parent;
	Node node;
	ObjectIdentifier objectId;
	PropertyIdentifier propertyId;
	
	public LocalBacnetProperty(LocalBacnetPoint point, Node parent, Node node){
		this.parent = parent;
		this.node = node;
		this.bacnetPoint = point;
		this.bacnetObj = this.bacnetPoint.getBacnetObj();
	}
	
	public LocalBacnetProperty(ObjectIdentifier oid, PropertyIdentifier pid, LocalBacnetPoint point, Node parent, Node node){
		this(point, parent, node);
		
		this.objectId = oid;
		this.propertyId = pid;
	}

    public void update(){
    	
    }
}
