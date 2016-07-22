package bacnet.properties;

import java.util.ArrayList;
import java.util.List;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Writable;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValuePair;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.enumerated.FileAccessMethod;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;

import bacnet.LocalBacnetPoint;


public class LocalFileAccessMethodProperty extends LocalBacnetProperty{
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(LocalUnitsProperty.class);
	}

	static final String ATTRIBUTE_EVENT_STATE = "event state";
	static final String ACTION_EDIT = "edit";

	FileAccessMethod method;

	public LocalFileAccessMethodProperty(LocalBacnetPoint point, Node parent, Node node) {
		super(point, parent, node);

	}
	
	public LocalFileAccessMethodProperty(ObjectIdentifier oid, PropertyIdentifier pid, LocalBacnetPoint point, Node parent, Node node){
		super(oid, pid, point, parent, node);
		
		bacnetObj.writeProperty(PropertyIdentifier.fileAccessMethod, FileAccessMethod.streamAccess);
		node.setValueType(ValueType.makeEnum(enumeratedNames()));
		node.setValue(new Value(FileAccessMethod.streamAccess.toString()));
		node.setWritable(Writable.WRITE);
		node.getListener().setValueHandler(new SetHandler());
	}

	private  List<String> enumeratedNames(){
		List<String> lst = new ArrayList<String>();
		for (FileAccessMethod method: FileAccessMethod.ALL) {
			lst.add(method.toString());
		}
		return lst;
	}
	
	private class SetHandler implements Handler<ValuePair> {

		@Override
		public void handle(ValuePair event) {
			if (!event.isFromExternalSource()) return;
			Value newVal = event.getCurrent();
			method = parseFileAccessMethod(newVal.getString());
			bacnetObj.writeProperty(PropertyIdentifier.fileAccessMethod, method);	
			node.setAttribute(propertyId.toString(), newVal);
		}
	}


	protected FileAccessMethod parseFileAccessMethod(String methodString) {

		for (FileAccessMethod method : FileAccessMethod.ALL){
			if(method.toString().equals(methodString)){
				return method;
			}
		}

		return null;
	}
	
	public void updatePropertyValue(Encodable enc) {
		if (enc instanceof FileAccessMethod) {
			method = (FileAccessMethod) enc;
			node.setValue(new Value(method.toString()));
		}
	}
}

