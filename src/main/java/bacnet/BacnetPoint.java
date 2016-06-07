package bacnet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.NodeBuilder;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.Writable;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.actions.ResultType;
import org.dsa.iot.dslink.node.actions.table.Row;
import org.dsa.iot.dslink.node.actions.table.Table;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValuePair;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.CompleteHandler;
import org.dsa.iot.dslink.util.handler.Handler;
import org.dsa.iot.dslink.util.json.JsonArray;
import org.dsa.iot.historian.database.Database;
import org.dsa.iot.historian.database.DatabaseProvider;
import org.dsa.iot.historian.stats.GetHistory;
import org.dsa.iot.historian.utils.QueryData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.enums.Month;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.obj.ObjectProperties;
import com.serotonin.bacnet4j.service.acknowledgement.ReadRangeAck;
import com.serotonin.bacnet4j.service.confirmed.ReadRangeRequest;
import com.serotonin.bacnet4j.service.confirmed.ReadRangeRequest.ByPosition;
import com.serotonin.bacnet4j.service.confirmed.ReadRangeRequest.BySequenceNumber;
import com.serotonin.bacnet4j.service.confirmed.ReadRangeRequest.ByTime;
import com.serotonin.bacnet4j.service.confirmed.WritePropertyRequest;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.constructed.BACnetError;
import com.serotonin.bacnet4j.type.constructed.DateTime;
import com.serotonin.bacnet4j.type.constructed.LogRecord;
import com.serotonin.bacnet4j.type.constructed.PriorityArray;
import com.serotonin.bacnet4j.type.constructed.PropertyValue;
import com.serotonin.bacnet4j.type.constructed.SequenceOf;
import com.serotonin.bacnet4j.type.enumerated.BinaryPV;
import com.serotonin.bacnet4j.type.enumerated.LifeSafetyState;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.Date;
import com.serotonin.bacnet4j.type.primitive.Null;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.Real;
import com.serotonin.bacnet4j.type.primitive.SignedInteger;
import com.serotonin.bacnet4j.type.primitive.Time;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import com.serotonin.bacnet4j.util.PropertyReferences;
import com.serotonin.bacnet4j.util.RequestUtils;

import bacnet.DeviceFolder.CovListener;
import bacnet.DeviceFolder.DataType;

public class BacnetPoint {
    private static final Logger LOGGER;

    static {
        LOGGER = LoggerFactory.getLogger(BacnetPoint.class);
    }

    private static PointCounter numPoints = new PointCounter();

    private static final int MAX_SUBS_PER_POINT = 2;
    private final CovListener listener;
    private final boolean[] subscribed = new boolean[MAX_SUBS_PER_POINT];
    private boolean covSub;

    DeviceFolder folder;
    private Node parent;

    Node node;
    ObjectIdentifier oid;
    private PropertyIdentifier pid;
    int id;
    //private DeviceEventAdapter listener;
    
    byte typeid = Null.TYPE_ID;
    
    private int defaultPriority;
    private int objectTypeId;
    private int instanceNumber;
    private String objectTypeDescription;
    private String objectName;
    private String presentValue;
    private boolean cov;
    private boolean settable;
    private String engineeringUnits;
    
    // for schedules
    private String effectivePeriod = null;
    private JsonArray weeklySchedule = null;
    private JsonArray exceptionSchedule = null;
    private JsonArray dateList = null;
    
    //for trend logs
    private String startTime = null;
    private String stopTime = null;
    //private String logdevObject = null;
    private int recordCount = -1;
    private int bufferSize = -1;
    private String logBuffer = null;
    private String referenceDevice;
    private String referenceObject;
    private String referenceProperty;
    
    //for notification classes
    private JsonArray priority = null;
    private JsonArray ackRequired = null;
    private JsonArray recipientList = null;

    private DataType dataType;
    private List<String> unitsDescription = new ArrayList<String>();

	private boolean historyInitialized = false;

    public BacnetPoint(DeviceFolder folder, Node parent, ObjectIdentifier oid) {
        this.folder = folder;
        this.listener = folder.new CovListener(this);
        this.parent = parent;
        this.node = null;
        this.oid = oid;
        id = numPoints.increment();
        this.defaultPriority = 8;

        setObjectTypeId(oid.getObjectType().intValue());
        setObjectTypeDescription(oid.getObjectType().toString());
        setInstanceNumber(oid.getInstanceNumber());
        setDataType(DeviceFolder.getDataType(oid.getObjectType()));

        if (DeviceFolder.isOneOf(oid.getObjectType(), ObjectType.binaryInput, ObjectType.binaryOutput,
                ObjectType.binaryValue)) {
            getUnitsDescription().add("0");
            getUnitsDescription().add("1");
        }
    }

    public BacnetPoint(DeviceFolder folder, Node parent, Node node) {
        this.folder = folder;
        this.listener = folder.new CovListener(this);
        this.parent = parent;
        this.node = node;
        ObjectType ot = DeviceFolder.parseObjectType(node.getAttribute("object type").getString());
        int instNum = node.getAttribute("object instance number").getNumber().intValue();
        boolean usecov = node.getAttribute("use COV").getBool();
        boolean canset = node.getAttribute("settable").getBool();
        int defprio = node.getAttribute("default priority").getNumber().intValue();
        Value pidval = node.getAttribute("pid");
        this.oid = new ObjectIdentifier(ot, instNum);

        if (pidval != null) pid = new PropertyIdentifier(pidval.getNumber().intValue());

        setCov(usecov);
        setSettable(canset);
        setDefaultPriority(defprio);
        try {
            setObjectTypeId(ot.intValue());

            setObjectTypeDescription(ot.toString());
            setInstanceNumber(instNum);
            setDataType(DeviceFolder.getDataType(ot));

            if (DeviceFolder.isOneOf(ot, ObjectType.binaryInput, ObjectType.binaryOutput,
                    ObjectType.binaryValue)) {
                getUnitsDescription().add("0");
                getUnitsDescription().add("1");
            }
            setupNode();
        }
        catch (Exception e)
        {
            LOGGER.debug("Object Type Error: ( " + node.getAttribute("object type").getString() + " )", e);
        }
    }

    public int getObjectTypeId() {
        return objectTypeId;
    }

    public void setObjectTypeId(int objectTypeId) {
        this.objectTypeId = objectTypeId;
//        if (node != null) {
//        	Node vnode = node.getChild("objectTypeId");
//        	if (vnode != null) vnode.setValue(new Value(objectTypeId));
//        	else node.createChild("objectTypeId").setValueType(ValueType.NUMBER).setValue(new Value(objectTypeId)).build();
//        	System.out.println("objectTypeID updated to " + objectTypeId);
//        }
    }

    public int getInstanceNumber() {
        return instanceNumber;
    }

    public void setInstanceNumber(int instanceNumber) {
        this.instanceNumber = instanceNumber;
//        if (node != null) {
//        	Node vnode = node.getChild("instanceNumber");
//        	if (vnode != null) vnode.setValue(new Value(instanceNumber));
//        	else node.createChild("instanceNumber").setValueType(ValueType.NUMBER).setValue(new Value(instanceNumber)).build();
//        	System.out.println("instanceNumber updated to " + instanceNumber);
//        }
    }

    public String getObjectTypeDescription() {
        return objectTypeDescription;
    }

    public void setObjectTypeDescription(String objectTypeDescription) {
        this.objectTypeDescription = objectTypeDescription;
//        if (node != null && objectTypeDescription != null) {
//        	Node vnode = node.getChild("objectTypeDescription");
//        	if (vnode != null) vnode.setValue(new Value(objectTypeDescription));
//        	else node.createChild("objectTypeDescription").setValueType(ValueType.STRING).setValue(new Value(objectTypeDescription)).build();
//        	System.out.println("objectTypeDescription updated to " + objectTypeDescription);
//        }
    }

    public String getObjectName() {
        return objectName;
    }

    public void setObjectName(String objectName) {
        if (objectName == null) return;
        this.objectName = objectName;
        if (node != null) {
//        	Node vnode = node.getChild("objectName");
//        	if (vnode != null) vnode.setValue(new Value(objectName));
//        	else node.createChild("objectName").setValueType(ValueType.STRING).setValue(new Value(objectName)).build();
//        	System.out.println("objectName updated to " + objectName);
        } else {
            setupNode();
        }
    }

    private void setupNode() {
        if (node == null) {
            String name = objectName;
            if (!(objectTypeDescription.startsWith("Analog")
                    || objectTypeDescription.startsWith("Binary"))) {
                name += " - " + objectTypeDescription;
            }
            NodeBuilder b = parent.createChild(name);
            b.setDisplayName(objectName);
            b.setValueType(ValueType.STRING);
            b.setValue(new Value(""));
            node = b.build();
        }
        if (node.getValue() == null) {
            node.setValueType(ValueType.STRING);
            node.setValue(new Value(""));
        }
        node.setAttribute("object type", new Value(objectTypeDescription));
        node.setAttribute("object instance number", new Value(instanceNumber));
        node.setAttribute("use COV", new Value(cov));
        node.setAttribute("settable", new Value(settable));
        node.setAttribute("default priority", new Value(defaultPriority));
        node.setAttribute("restore type", new Value("point"));

        if (node.getChild("present value") == null) {
            node.createChild("present value").setValueType(ValueType.STRING).setValue(new Value("")).build();
        }
        node.getChild("present value").setWritable(Writable.NEVER);
        folder.conn.link.setupPoint(this, folder);
        
//        if (DeviceFolder.isOneOf(oid.getObjectType(), ObjectType.trendLog)) {
//        	
//        	Action act = new Action(Permission.READ, new GetLogHandler(0));
//        	act.addParameter(new Parameter("position", ValueType.NUMBER, new Value(0)));
//        	act.addParameter(new Parameter("count", ValueType.NUMBER, new Value(0)));
//        	act.addResult(new Parameter("Timestamp", ValueType.STRING));
//        	act.addResult(new Parameter("Status Flags", ValueType.STRING));
//        	act.addResult(new Parameter("Data", ValueType.STRING));
//			act.setResultType(ResultType.TABLE);
//        	Node anode = node.getChild("Get Log by Position");
//        	if (anode == null) node.createChild("Get Log by Position").setAction(act).build().setSerializable(false);
//        	else anode.setAction(act);
//        	
//        	act = new Action(Permission.READ, new GetLogHandler(1));
//        	act.addParameter(new Parameter("sequence number", ValueType.NUMBER, new Value(0)));
//        	act.addParameter(new Parameter("count", ValueType.NUMBER, new Value(0)));
//        	act.addResult(new Parameter("Timestamp", ValueType.STRING));
//        	act.addResult(new Parameter("Status Flags", ValueType.STRING));
//        	act.addResult(new Parameter("Data", ValueType.STRING));
//			act.setResultType(ResultType.TABLE);
//        	anode = node.getChild("Get Log by Sequence Number");
//        	if (anode == null) node.createChild("Get Log by Sequence Number").setAction(act).build().setSerializable(false);
//        	else anode.setAction(act);
//        	
//        	act = new Action(Permission.READ, new GetLogHandler(2));
//        	act.addParameter(new Parameter("time", ValueType.STRING));
//        	act.addParameter(new Parameter("count", ValueType.NUMBER, new Value(0)));
//        	act.addResult(new Parameter("Timestamp", ValueType.STRING));
//        	act.addResult(new Parameter("Status Flags", ValueType.STRING));
//        	act.addResult(new Parameter("Data", ValueType.STRING));
//			act.setResultType(ResultType.TABLE);
//        	anode = node.getChild("Get Log by Time");
//        	if (anode == null) node.createChild("Get Log by Time").setAction(act).build().setSerializable(false);
//        	else anode.setAction(act);
//        }
        
//        setObjectTypeId(objectTypeId);
//        setInstanceNumber(instanceNumber);
//        setObjectTypeDescription(objectTypeDescription);
//        if (presentValue != null) setPresentValue(presentValue, pid);
//        else node.createChild("present value").setValueType(ValueType.STRING).setValue(new Value(" ")).build();
//        setCov(cov);
//        setEngineeringUnits(engineeringUnits);
//        setDataType(dataType);
//        setUnitsDescription(unitsDescription);
//        setReferenceDeviceId(referenceDeviceId);
//        setReferenceObjectTypeId(referenceObjectTypeId);
//        setReferenceObjectTypeDescription(referenceObjectTypeDescription);
//        setReferenceInstanceNumber(referenceInstanceNumber);
//        clearActions();
        makeActions();
        update();

        //if (listener!=null) folder.conn.localDevice.getEventHandler().removeListener(listener);


    }

    private void makeActions() {
        Action act = new Action(Permission.READ, new RemoveHandler());
        Node anode = node.getChild("remove");
        if (anode == null) node.createChild("remove").setAction(act).build().setSerializable(false);
        else anode.setAction(act);

        act = new Action(Permission.READ, new EditHandler());
        act.addParameter(new Parameter("name", ValueType.STRING, new Value(node.getName())));
        act.addParameter(new Parameter("object type", ValueType.makeEnum("Analog Input", "Analog Output", "Analog Value", "Binary Input", "Binary Output", "Binary Value", "Calendar", "Command", "Device", "Event Enrollment", "File", "Group", "Loop", "Multi-state Input", "Multi-state Output", "Notification Class", "Program", "Schedule", "Averaging", "Multi-state Value", "Trend Log", "Life Safety Point", "Life Safety Zone", "Accumulator", "Pulse Converter", "Event Log", "Trend Log Multiple", "Load Control", "Structured View", "Access Door"), node.getAttribute("object type")));
        act.addParameter(new Parameter("object instance number", ValueType.NUMBER, node.getAttribute("object instance number")));
        act.addParameter(new Parameter("use COV", ValueType.BOOL, node.getAttribute("use COV")));
        act.addParameter(new Parameter("settable", ValueType.BOOL, node.getAttribute("settable")));
        act.addParameter(new Parameter("default priority", ValueType.NUMBER, node.getAttribute("default priority")));
        anode = node.getChild("edit");
        if (anode == null) node.createChild("edit").setAction(act).build().setSerializable(false);
        else anode.setAction(act);

        act = new Action(Permission.READ, new CopyHandler());
        act.addParameter(new Parameter("name", ValueType.STRING));
        anode = node.getChild("make copy");
        if (anode == null) node.createChild("make copy").setAction(act).build().setSerializable(false);
        else anode.setAction(act);

    }

//    private void clearActions() {
//    	if (node == null || node.getChildren() == null) return;
//    	for (Node child: node.getChildren().values()) {
//    		if (child.getAction() != null) {
//    			node.removeChild(child);
//    		}
//    	}
//    }

//    private class SetHandler implements Handler<ActionResult> {
//    	private int priority;
//    	SetHandler(int p) {
//    		priority = p;
//    	}
//    	public void handle(ActionResult event) {
//    		Value newval = event.getParameter("value", ValueType.STRING);
//    		handleSet(newval, priority, false);
//    	}
//    }

    private class GetLogHandler implements Handler<ActionResult> {
    	private int choice;
    	GetLogHandler(int c) {
    		choice = c;
    	}
    	
    	public void handle(ActionResult event) {
    		ReadRangeRequest request = null;
    		int count = event.getParameter("count", ValueType.NUMBER).getNumber().intValue();
    		if (choice == 0) {
    			int pos = event.getParameter("position", ValueType.NUMBER).getNumber().intValue();
    			ByPosition bypos = new ByPosition(new UnsignedInteger(pos), new SignedInteger(count));
    			request = new ReadRangeRequest(oid, PropertyIdentifier.logBuffer, null, bypos);
    		} else if (choice == 1) {
    			int seqnum = event.getParameter("sequence number", ValueType.NUMBER).getNumber().intValue();
    			BySequenceNumber byseq = new BySequenceNumber(new UnsignedInteger(seqnum), new SignedInteger(count));
    			request = new ReadRangeRequest(oid, PropertyIdentifier.logBuffer, null, byseq);
    		} else if (choice == 2) {
    			String dtstr = event.getParameter("time", ValueType.STRING).getString();
    			Date d = new Date(Integer.parseInt(dtstr.substring(0, 4)), Month.valueOf(Integer.parseInt(dtstr.substring(5, 7))), Integer.parseInt(dtstr.substring(8, 10)), null);
    			Time t = new Time(Integer.parseInt(dtstr.substring(11, 13)), Integer.parseInt(dtstr.substring(14, 16)), Integer.parseInt(dtstr.substring(17, 19)), Integer.parseInt(dtstr.substring(20, 22)));
    			DateTime dt = new DateTime(d, t);
    			ByTime bytime = new ByTime(dt, new SignedInteger(count));
    			request = new ReadRangeRequest(oid, PropertyIdentifier.logBuffer, null, bytime);
    		}
    		
    		if (request != null) {
    			ReadRangeAck response = null;
				try {
					response = (ReadRangeAck) folder.conn.localDevice.send(folder.root.device, request).get();
				} catch (BACnetException e) {
					LOGGER.debug("", e);
				}
				
				if (response != null) {
					Table table = event.getTable();
					for (LogRecord record: (SequenceOf<LogRecord>) response.getItemData()) {
						Value ts = new Value(Utils.datetimeToString(record.getTimestamp()));
						Value sf = new Value(record.getStatusFlags().toString());
						Value data = new Value(record.getEncodable().toString());
						Row row = Row.make(ts, sf, data);
						table.addRow(row);
					}
				}
    		}
    	}
    }
    
    private class PropertySetHandler implements Handler<ValuePair> {
    	PropertyIdentifier prop;
    	PropertySetHandler(PropertyIdentifier p) {
    		prop = p;
    	}
    	
    	public void handle(ValuePair event) {
    		if (!event.isFromExternalSource()) {
    			return;
    		}
    		JsonArray newval = event.getCurrent().getArray();
    		Encodable enc = Utils.encodeJsonArray(newval, prop, typeid);
    		if (enc == null) return;
    		
    		folder.conn.localDevice.send(folder.root.device, new WritePropertyRequest(oid, prop, null, enc, new UnsignedInteger(defaultPriority)));
    		
    	}
    }
    
    private class RawSetHandler implements Handler<ValuePair> {
        private int priority;

        RawSetHandler(int p) {
            priority = p;
        }

        public void handle(ValuePair event) {
            if (!event.isFromExternalSource()) {
                return;
            }
            Value newval = event.getCurrent();
            int p = (priority > -1) ? priority : defaultPriority;
            handleSet(newval, p, true);
        }
    }

    private void handleSet(Value newval, int priority, boolean raw) {
        if (folder.conn.localDevice == null) {
            folder.conn.stop();
            return;
        }
        if (folder.root.device == null) return;
        if (dataType == DataType.BINARY) {
            if (raw) {
//				newval = String.valueOf(Boolean.parseBoolean(newval) || newval.equals("1"));
            } else {
                String on = (unitsDescription.size() > 1) ? unitsDescription.get(1) : "1";
                newval = new Value(String.valueOf(newval.getString().equals(on)));
            }
        } else if (dataType == DataType.MULTISTATE) {
            if (!raw) {
                int i = unitsDescription.indexOf(newval.getString());
                if (i == -1) return;
                newval = new Value(String.valueOf(i));
            }
        }
        Encodable enc = valueToEncodable(newval, oid.getObjectType(), pid);
        try {
            LOGGER.debug("Sending write request");
            folder.conn.localDevice.send(folder.root.device, new WritePropertyRequest(oid, pid, null, enc, new UnsignedInteger(priority)));
            //Thread.sleep(500);
        } catch (Exception e) {
            LOGGER.debug("error: ", e);
        }
        refreshPriorities();
    }

    private class EditHandler implements Handler<ActionResult> {
        public void handle(ActionResult event) {
            String newname = event.getParameter("name", ValueType.STRING).getString();
            if (newname != null && newname.length() > 0 && !newname.equals(node.getName())) {
                parent.removeChild(node);
                node = parent.createChild(newname).build();
            }
            settable = event.getParameter("settable", ValueType.BOOL).getBool();
            cov = event.getParameter("use COV", ValueType.BOOL).getBool();
            defaultPriority = event.getParameter("default priority", ValueType.NUMBER).getNumber().intValue();
            ObjectType ot = DeviceFolder.parseObjectType(event.getParameter("object type", ValueType.STRING).getString());
            instanceNumber = event.getParameter("object instance number", ValueType.NUMBER).getNumber().intValue();
            oid = new ObjectIdentifier(ot, instanceNumber);
            setObjectTypeId(ot.intValue());
            setObjectTypeDescription(ot.toString());
            setDataType(DeviceFolder.getDataType(ot));

            if (DeviceFolder.isOneOf(ot, ObjectType.binaryInput, ObjectType.binaryOutput,
                    ObjectType.binaryValue)) {
                getUnitsDescription().add("0");
                getUnitsDescription().add("1");
            }
            setupNode();
            //folder.conn.link.setupPoint(getMe(), folder);
        }
    }

    private class RemoveHandler implements Handler<ActionResult> {
        public void handle(ActionResult event) {
            node.clearChildren();
            parent.removeChild(node);
        }
    }

    private class CopyHandler implements Handler<ActionResult> {
        public void handle(ActionResult event) {
            String name = event.getParameter("name", ValueType.STRING).getString();
            Node newnode = parent.createChild(name).build();
            newnode.setAttribute("object type", new Value(objectTypeDescription));
            newnode.setAttribute("object instance number", new Value(instanceNumber));
            newnode.setAttribute("use COV", new Value(cov));
            newnode.setAttribute("settable", new Value(settable));
            newnode.setAttribute("default priority", new Value(defaultPriority));
            newnode.setAttribute("restore type", new Value("point"));
            new BacnetPoint(folder, parent, newnode);
        }
    }

    public DataType getDataType() {
        return dataType;
    }

    public void setDataType(DataType dataType) {
        if (dataType == DataType.NUMERIC && presentValue != null) {
            try {
                Double.parseDouble(presentValue);
            } catch (NumberFormatException e) {
                dataType = DataType.ALPHANUMERIC;
            }
        }
        this.dataType = dataType;
//        if (node != null && dataType != null) {
//        	Node vnode = node.getChild("dataType");
//        	if (vnode != null) vnode.setValue(new Value(dataType.toString()));
//        	else node.createChild("dataType").setValueType(ValueType.STRING).setValue(new Value(dataType.toString())).build();
//        	System.out.println("dataType updated to " + dataType);
//        }
    }

    public List<String> getUnitsDescription() {
        return unitsDescription;
    }

    public void setUnitsDescription(List<String> unitsDescription) {
        this.unitsDescription = unitsDescription;
//        if (node != null) {
//        	Node vnode = node.getChild("unitsDescription");
//        	if (vnode != null) vnode.setValue(new Value(unitsDescription.toString()));
//        	else node.createChild("unitsDescription").setValueType(ValueType.STRING).setValue(new Value(unitsDescription.toString())).build();
//        	System.out.println("unitsDescription updated to " + unitsDescription);
//        }
    }
    
    public String getEffectivePeriod() {
    	return effectivePeriod;
    }
    
    public void setEffectivePeriod(String ep) {
    	this.effectivePeriod = ep;
    }
    
    public JsonArray getWeeklySchedule() {
    	return weeklySchedule;
    }
    
    public void setWeeklySchedule(JsonArray ws) {
    	this.weeklySchedule = ws;
    }
    
    public JsonArray getExceptionSchedule() {
    	return exceptionSchedule;
    }
    
    public void setExceptionSchedule(JsonArray es) {
    	this.exceptionSchedule = es;
    }
    
    public JsonArray getDateList() {
    	return dateList;
    }
    
    public void setDateList(JsonArray dl) {
    	this.dateList = dl;
    }
    
    public void setStartTime(String start) {
    	this.startTime = start;
    }
    
    public void setStopTime(String stop) {
    	this.stopTime = stop;
    }
    
    public void setLogBuffer(String buff) {
    	this.logBuffer = buff;
    }
    
    public void setRecordCount(int count) {
    	this.recordCount = count;
    }
    
    public void setBufferSize(int size) {
    	this.bufferSize = size;
    }
    
    public void setPriority(JsonArray priority) {
    	this.priority = priority;
    }
    
    public void setAckRequired(JsonArray ackreq) {
    	this.ackRequired = ackreq;
    }
    
    public void setRecipientList(JsonArray reclist) {
    	this.recipientList = reclist;
    }

    public String getPresentValue() {
        return presentValue;
    }

    public void setPresentValue(String presentValue, PropertyIdentifier pid) {
        this.pid = pid;
        node.setAttribute("pid", new Value(pid.intValue()));
        this.presentValue = presentValue;
        setDataType(dataType);
//        if (node != null && presentValue != null) {
//        	Node vnode = node.getChild("present value");
//        	if (vnode != null) vnode.setValue(new Value(presentValue));
//        	else vnode = node.createChild("present value").setValueType(ValueType.STRING).setValue(new Value(presentValue)).build();
//        	System.out.println("presentValue updated to " + presentValue);
//        	
//        	vnode.removeChild("set");
//        	
//        	Action act = new Action(Permission.READ, new SetHandler());
//        	act.addParameter(new Parameter("value", ValueType.STRING, new Value(presentValue)));
//        	vnode.createChild("set").setAction(act).build().setSerializable(false);
//        }
    }

    public boolean isCov() {
        return cov;
    }

    public void setCov(boolean cov) {
        this.cov = cov;
//        if (node != null) {
//        	Node vnode = node.getChild("cov");
//        	if (vnode != null) vnode.setValue(new Value(cov));
//        	else node.createChild("cov").setValueType(ValueType.BOOL).setValue(new Value(cov)).build();
//        	System.out.println("cov updated to " + cov);
//        }
    }

    public boolean isSettable() {
        return settable;
    }

    public void setSettable(boolean settable) {
        this.settable = settable;

    }
    
    public int getDefaultPriority() {
    	return defaultPriority;
    }
    
    public void setDefaultPriority(int p) {
    	this.defaultPriority = p;
    }

    public String getEngineeringUnits() {
        return engineeringUnits;
    }

    public void setEngineeringUnits(String engineeringUnits) {
        this.engineeringUnits = engineeringUnits;
//        if (node != null && engineeringUnits != null) {
//        	Node vnode = node.getChild("engineeringUnits");
//        	if (vnode != null) vnode.setValue(new Value(engineeringUnits));
//        	else node.createChild("engineeringUnits").setValueType(ValueType.STRING).setValue(new Value(engineeringUnits)).build();
//        	System.out.println("engineeringUnits updated to " + engineeringUnits);
//        }
    }

    public String getReferenceDevice() {
        return referenceDevice;
    }

    public void setReferenceDevice(String referenceDevice) {
        this.referenceDevice = referenceDevice;
//        if (node != null) {
//        	Node vnode = node.getChild("referenceDeviceId");
//        	if (vnode != null) vnode.setValue(new Value(referenceDeviceId));
//        	else node.createChild("referenceDeviceId").setValueType(ValueType.NUMBER).setValue(new Value(referenceDeviceId)).build();
//        	System.out.println("referenceDeviceId updated to " + referenceDeviceId);
//        }
    }

    public String getReferenceObject() {
        return referenceObject;
    }

    public void setReferenceObject(String referenceObject) {
        this.referenceObject = referenceObject;
//        if (node != null && referenceObjectTypeDescription != null) {
//        	Node vnode = node.getChild("referenceObjectTypeDescription");
//        	if (vnode != null) vnode.setValue(new Value(referenceObjectTypeDescription));
//        	else node.createChild("referenceObjectTypeDescription").setValueType(ValueType.STRING).setValue(new Value(referenceObjectTypeDescription)).build();
//        	System.out.println("referenceObjectTypeDescription updated to " + referenceObjectTypeDescription);
//        }
    }

    public String getReferenceProperty() {
    	return referenceProperty;
    }
    
    public void setReferenceProperty(String referenceProperty) {
    	this.referenceProperty = referenceProperty;
    }

    private Encodable valueToEncodable(Value value, ObjectType objectType, PropertyIdentifier pid) {
        Class<? extends Encodable> clazz = ObjectProperties.getPropertyTypeDefinition(objectType, pid).getClazz();

        switch (dataType) {
            case BINARY: {
                boolean b;
                if (value.getType().compare(ValueType.BOOL)) b = value.getBool();
                else b = (Boolean.parseBoolean(value.getString()) || value.getString().equals("1"));
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
                double d;
                if (value.getType() == ValueType.NUMBER) d = value.getNumber().doubleValue();
                else d = Double.parseDouble(value.getString());
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
            case ALPHANUMERIC: {
                if (clazz == BinaryPV.class) {
                    boolean b;
                    if (value.getType().compare(ValueType.BOOL)) b = value.getBool();
                    else b = (Boolean.parseBoolean(value.getString()) || value.getString().equals("1"));
                    if (b)
                        return BinaryPV.active;
                    return BinaryPV.inactive;
                }

                if (clazz == UnsignedInteger.class) {
                    int i = Integer.parseInt(value.getString());
                    if (value.getType() == ValueType.NUMBER) i = value.getNumber().intValue();
                    return new UnsignedInteger(i);
                }
                if (clazz == LifeSafetyState.class) {
                    int i = Integer.parseInt(value.getString());
                    if (value.getType() == ValueType.NUMBER) i = value.getNumber().intValue();
                    return new LifeSafetyState(i);
                }
                if (clazz == Real.class) {
                    float f = (float) Double.parseDouble(value.getString());
                    if (value.getType() == ValueType.NUMBER) f = value.getNumber().floatValue();
                    return new Real(f);
                }
            }
            case MULTISTATE: {
                int i = Integer.parseInt(value.getString());
                if (value.getType().compare(ValueType.ENUM)) i = unitsDescription.indexOf(value.getString());
                if (clazz == BinaryPV.class) {
                    if (i != 0)
                        return BinaryPV.active;
                    return BinaryPV.inactive;
                }

                if (clazz == UnsignedInteger.class)
                    return new UnsignedInteger(i);

                if (clazz == LifeSafetyState.class)
                    return new LifeSafetyState(i);

                if (clazz == Real.class)
                    return new Real(i);
            }
            default:
                return BinaryPV.inactive;
        }

    }

    private static boolean areEqual(ValueType a, ValueType b) {
        return a == b || (a != null && b != null && a.toJsonString().equals(b.toJsonString()));
    }

    void update() {
        if (node == null) return;

        if (objectName != null) {
            Node vnode = node.getChild("objectName");
            if (vnode != null) {
                if (!new Value(objectName).equals(vnode.getValue())) {
                    vnode.setValue(new Value(objectName));
                    LOGGER.debug("objectName updated to " + objectName);
                }
            } else {
                node.createChild("objectName").setValueType(ValueType.STRING).setValue(new Value(objectName)).build();
                LOGGER.debug("objectName set to " + objectName);
            }

        }
        if (dataType != null) {
            Node vnode = node.getChild("dataType");
            if (vnode != null) {
                if (!new Value(dataType.toString()).equals(vnode.getValue())) {
                    vnode.setValue(new Value(dataType.toString()));
                    LOGGER.debug("dataType updated to " + dataType);
                }
            } else {
                node.createChild("dataType").setValueType(ValueType.STRING).setValue(new Value(dataType.toString())).build();
                LOGGER.debug("dataType set to " + dataType);
            }
        }
        Node vnode = node.getChild("present value");
        Value oldval = null;
        if (vnode != null) oldval = vnode.getValue();
        if (presentValue != null) {
            //String prettyVal = getPrettyPresentValue(objectTypeId, presentValue, unitsDescription, referenceObjectTypeDescription, referenceInstanceNumber, referenceDeviceId);
            ValueType vt;
            Value val;
            switch (dataType) {
                case BINARY: {
                    String off = (unitsDescription.size() > 0) ? unitsDescription.get(0) : "0";
                    String on = (unitsDescription.size() > 1) ? unitsDescription.get(1) : "1";
                    vt = ValueType.makeBool(on, off);
                    val = new Value(Boolean.parseBoolean(presentValue) || presentValue.equals("1")
                            || presentValue.equals("Active"));
                    break;
                }
                case NUMERIC: {
                    vt = ValueType.NUMBER;
                    val = new Value(Double.parseDouble(presentValue));
                    break;
                }
                case MULTISTATE: {
                    Set<String> enums = new HashSet<String>(unitsDescription);
                    vt = ValueType.makeEnum(enums);
                    int index = Integer.parseInt(presentValue) - 1;
                    if (index >= 0 && index < unitsDescription.size()) val = new Value(unitsDescription.get(index));
                    else {
                    	vt = ValueType.STRING;
                    	val = new Value(presentValue);
                    }
                    break;
                }
                case ALPHANUMERIC: {
                    vt = ValueType.STRING;
                    val = new Value(presentValue);
                    break;
                }
                default: {
                    vt = ValueType.STRING;
                    val = new Value(presentValue);
                }
            }
            if (!areEqual(vt, node.getValueType()) || !val.equals(node.getValue())) {
                node.setValueType(vt);
                node.setValue(val);
            }
            Value units = null;
            if (!(DeviceFolder.isOneOf(objectTypeId, ObjectType.binaryInput, ObjectType.binaryOutput,
                    ObjectType.binaryValue, ObjectType.multiStateInput, ObjectType.multiStateOutput,
                    ObjectType.multiStateValue, ObjectType.lifeSafetyPoint, ObjectType.lifeSafetyZone,
                    ObjectType.trendLog)) && unitsDescription.size() > 0) {
                units = new Value(unitsDescription.get(0));
            }
            Node unode = node.getChild("units");
            if (unode != null) {
                if (units == null) node.removeChild("units");
                else if (!units.equals(unode.getValue())) unode.setValue(units);
            } else {
                if (units != null) node.createChild("units").setValueType(ValueType.STRING).setValue(units).build();
            }
            if (vnode != null) {
                if (!areEqual(vt, vnode.getValueType()) || !val.equals(vnode.getValue())) {
                    vnode.setValueType(vt);
                    vnode.setValue(val);
                    LOGGER.debug("presentValue updated to " + val);
                }
            } else {
                vnode = node.createChild("present value").setValueType(vt).setValue(val).build();
                LOGGER.debug("presentValue set to " + val);
            }
        }
        
        updateProperty("effective period", effectivePeriod);
        updateProperty("weekly schedule", weeklySchedule, PropertyIdentifier.weeklySchedule);
        updateProperty("exception schedule", exceptionSchedule, PropertyIdentifier.exceptionSchedule);
        
        updateProperty("date list", dateList, PropertyIdentifier.dateList);
        
        updateProperty("device reference", referenceDevice);
        updateProperty("object reference", referenceObject);
        updateProperty("property reference", referenceProperty);
        updateProperty("start time", startTime);
        updateProperty("stop time", stopTime);
        updateProperty("record count", recordCount);
        updateProperty("buffer size", bufferSize);
        updateProperty("log buffer", logBuffer);
        
        updateProperty("priority", priority, PropertyIdentifier.priority);
        updateProperty("ack required", ackRequired, PropertyIdentifier.ackRequired);
        updateProperty("recipient list", recipientList, PropertyIdentifier.recipientList);

        if (bufferSize > -1 && !historyInitialized ) {
        	GetHistory.initAction(node, new Db());
        	historyInitialized = true;
        }
        
        if (settable) {
            if (vnode.getWritable() != Writable.WRITE) {
                makeSetAction(vnode, -1);
                makeSetAction(node, -1);
                PriorityArray pa = null;
//	        	try {
//	    			Thread.sleep(500);
//	    		} catch (InterruptedException e) {	
//	    			LOGGER.error("interrupted");
//	    		}
                try {
                    pa = getPriorityArray();
                } catch (BACnetException e) {
                    return;
                }
                Value newval = vnode.getValue();
                if (pa != null && (!newval.equals(oldval) || vnode.getChildren() == null || vnode.getChildren().size() < pa.getCount() + 3)) {
                    makeRelinquishAction(vnode, -1);
                    Action act = new Action(Permission.READ, new RelinquishAllHandler());
                    vnode.createChild("relinquish all").setAction(act).build().setSerializable(false);
                    refreshPriorities(pa);
                }
            } else {
            	refreshPriorities();
            }
        } else {
            if (vnode.getWritable() != Writable.NEVER) {
                vnode.clearChildren();
                vnode.setWritable(Writable.NEVER);
            }
        }

    }
    
    private void updateProperty(String name, String value) {
    	Node propnode = node.getChild(name);
    	if (value != null) {
        	if (propnode != null) propnode.setValue(new Value(value));
        	else node.createChild(name).setValueType(ValueType.STRING).setValue(new Value(value)).build();
        } else {
        	if (propnode != null) node.removeChild(propnode);
        }
    }
    
    private void updateProperty(String name, int value) {
    	Node propnode = node.getChild(name);
    	if (value >= 0) {
        	if (propnode != null) propnode.setValue(new Value(value));
        	else node.createChild(name).setValueType(ValueType.NUMBER).setValue(new Value(value)).build();
        } else {
        	if (propnode != null) node.removeChild(propnode);
        }
    }
    
    private void updateProperty(String name, JsonArray value, PropertyIdentifier p) {
    	Node propnode = node.getChild(name);
    	if (value != null) {
        	if (propnode != null) propnode.setValue(new Value(value));
        	else propnode = node.createChild(name).setValueType(ValueType.ARRAY).setValue(new Value(value)).build();
        	if (propnode.getWritable() != Writable.WRITE) {
        		propnode.setWritable(Writable.WRITE);
        		propnode.getListener().setValueHandler(new PropertySetHandler(p));
        	}
    	} else {
        	if (propnode != null) node.removeChild(propnode);
        }
    }

    private void refreshPriorities() {
        refreshPriorities(null);
    }

    private void refreshPriorities(PriorityArray priorities) {
        Node vnode = node.getChild("present value");
        if (priorities == null) {
            try {
                priorities = getPriorityArray();
            } catch (BACnetException e) {
                // TODO Auto-generated catch block
                LOGGER.error("error: ", e);
                return;
            }
        }
        if (priorities == null) return;
        for (int i = 1; i <= priorities.getCount(); i++) {
            Encodable enc = priorities.get(i).getValue();
            String p = enc.toString();
            ValueType vt;
            Value val = null;
            boolean isnull = (enc instanceof Null);
            switch (dataType) {
                case BINARY: {
                    String off = (unitsDescription.size() > 0) ? unitsDescription.get(0) : "0";
                    String on = (unitsDescription.size() > 1) ? unitsDescription.get(1) : "1";
                    vt = ValueType.makeBool(on, off);
                    if (!isnull) val = new Value(Boolean.parseBoolean(p) || p.equals("1") || p.equals("Active"));
                    break;
                }
                case NUMERIC: {
                    vt = ValueType.NUMBER;
                    if (!isnull) val = new Value(Double.parseDouble(p));
                    break;
                }
                case MULTISTATE: {
                    Set<String> enums = new HashSet<String>(unitsDescription);
                    vt = ValueType.makeEnum(enums);
                    int index = Integer.parseInt(p) - 1;
                    if (!isnull) {
                        if (index >= 0 && index < unitsDescription.size()) val = new Value(unitsDescription.get(index));
                        else val = new Value(p);
                    }
                    break;
                }
                case ALPHANUMERIC: {
                    vt = ValueType.STRING;
                    if (!isnull) val = new Value(p);
                    break;
                }
                default: {
                    vt = ValueType.STRING;
                    if (!isnull) val = new Value(p);
                }
            }
            Node pnode = vnode.getChild("Priority " + i);
            if (pnode != null) {
                pnode.setValueType(vt);
                pnode.setValue(val);
                if (pnode.getChild("relinquish") == null) {
                	makeSetAction(pnode, i);
                	makeRelinquishAction(pnode, i);
                }
            } else {
                pnode = vnode.createChild("Priority " + i).setValueType(vt).setValue(val).build();
                makeSetAction(pnode, i);
                makeRelinquishAction(pnode, i);
            }

        }
    }

    private void makeRelinquishAction(Node valnode, int priority) {
        Action act = new Action(Permission.READ, new RelinquishHandler(priority));
        valnode.createChild("relinquish").setAction(act).build().setSerializable(false);
    }

    private PriorityArray getPriorityArray() throws BACnetException {
        if (folder.conn.localDevice == null) {
            folder.conn.stop();
            return null;
        }
        if (folder.root.device == null) return null;
        Encodable e = RequestUtils.getProperty(folder.conn.localDevice, folder.root.device, oid, PropertyIdentifier.priorityArray);
        if (e instanceof BACnetError) return null;
        return (PriorityArray) e;
    }

    private class RelinquishAllHandler implements Handler<ActionResult> {
        public void handle(ActionResult event) {
            try {
                PriorityArray priorities = getPriorityArray();
                if (priorities == null) return;
                for (int i = 1; i <= priorities.getCount(); i++) {
                    relinquish(i);
                }
//				try {
//    				Thread.sleep(500);
//    			} catch (InterruptedException e) {
//    				LOGGER.error("interrupted");
//    			}
                refreshPriorities();
            } catch (BACnetException e) {
                // TODO Auto-generated catch block
                LOGGER.error("error: ", e);
            }
        }
    }

    private class RelinquishHandler implements Handler<ActionResult> {
        private int priority;

        RelinquishHandler(int p) {
            priority = p;
        }

        public void handle(ActionResult event) {
        	int p = (priority > -1) ? priority : defaultPriority;
            relinquish(p);
//    		try {
//				Thread.sleep(500);
//			} catch (InterruptedException e) {
//				LOGGER.error("interrupted");
//			}
            refreshPriorities();
        }
    }

    private void relinquish(int priority) {
        if (folder.conn.localDevice == null) {
            folder.conn.stop();
            return;
        }
        if (folder.root.device == null) return;
        try {
            folder.conn.localDevice.send(folder.root.device, new WritePropertyRequest(oid, pid, null, new Null(), new UnsignedInteger(priority)));
        } catch (Exception e) {
            LOGGER.error("error: ", e);
        }
    }

    private void makeSetAction(Node valnode, int priority) {
//    	Action act = new Action(Permission.READ, new SetHandler(priority));
//    	Parameter par;
//    	switch(dataType) {
//    	case BINARY: {
//    		String off = (unitsDescription.size() > 0) ? unitsDescription.get(0) : "0";
//    		String on = (unitsDescription.size() > 1) ? unitsDescription.get(1) : "1";
//    		String def = ("0".equals(presentValue)) ? off : on;
//    		par = new Parameter("value", ValueType.makeEnum(on, off), new Value(def));
//    		break;
//    	}
//    	case NUMERIC: {
//    		par = new Parameter("value", ValueType.STRING, new Value(presentValue));
//    		break;
//    	}
//    	case MULTISTATE: {
//    		Set<String> enums = new HashSet<String>(unitsDescription);
//    		par = new Parameter("value", ValueType.makeEnum(enums));
//    		try {
//                 int index = Integer.parseInt(presentValue) - 1;
//                 if (index >= 0 && index < unitsDescription.size())
//                	 par = new Parameter("value", ValueType.makeEnum(enums), new Value(unitsDescription.get(index)));
//            } catch (NumberFormatException e) {
//                 // no op
//            }
//    		break;
//    	}
//    	case ALPHANUMERIC: {
//    		par = new Parameter("value", ValueType.STRING, new Value(presentValue));
//    		break;
//    	}
//    	default: {
//    		par = new Parameter("value", ValueType.STRING, new Value(presentValue));
//    		break;
//    	}
//    	}
//    	act.addParameter(par);
//    	valnode.createChild("setPretty").setAction(act).build().setSerializable(false);
        valnode.setWritable(Writable.WRITE);
        valnode.getListener().setValueHandler(new RawSetHandler(priority));
    }

    public static String getPrettyPresentValue(int objectTypeId, String presentValue, List<String> unitsDescription,
                                               String referenceObjectTypeDescription, int referenceInstanceNumber, int referenceDeviceId) {
        if (DeviceFolder.isOneOf(objectTypeId, ObjectType.binaryInput, ObjectType.binaryOutput,
                ObjectType.binaryValue)) {
            if ("0".equals(presentValue)) {
                if (unitsDescription.size() > 0) return unitsDescription.get(0);
                else return "0";
            }
            if ("1".equals(presentValue)) {
                if (unitsDescription.size() > 1) return unitsDescription.get(1);
                else return "1";
            }
        } else if (DeviceFolder.isOneOf(objectTypeId, ObjectType.multiStateInput, ObjectType.multiStateOutput,
                ObjectType.multiStateValue)) {
            try {
                int index = Integer.parseInt(presentValue) - 1;
                if (index >= 0) {
                    if (index < unitsDescription.size()) return unitsDescription.get(index);
                    else return presentValue;
                }

            } catch (NumberFormatException e) {
                // no op
            }
        } else if (DeviceFolder.isOneOf(objectTypeId, ObjectType.lifeSafetyPoint, ObjectType.lifeSafetyZone)) {
            try {
                int index = Integer.parseInt(presentValue);
                return new LifeSafetyState(index).toString();
            } catch (NumberFormatException e) {
                // no op
            }
        } else if (DeviceFolder.isOneOf(objectTypeId, ObjectType.trendLog)) {
            if (StringUtils.isEmpty(referenceObjectTypeDescription))
                return "";
            return referenceObjectTypeDescription + " " + referenceInstanceNumber + " @ "
                    + ObjectType.device.toString() + " " + referenceDeviceId + ", " + presentValue;
        } else if (unitsDescription.size() > 0)
            return presentValue + " " + unitsDescription.get(0);

        return presentValue;
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

    private BacnetPoint getMe() {
        return this;
    }


    //polling
    void subscribe(int index, boolean iscov) {
        if (index >= MAX_SUBS_PER_POINT) return;
        boolean wasActive = isActive();
        boolean wasCov = covSub;
        subscribed[index] = true;
        covSub = iscov;
        if (!wasActive) {
            if (covSub) startCov();
            else startPoll();
        } else if (wasCov != covSub) {
            if (covSub) {
                stopPoll();
                startCov();
            } else {
                stopCov();
                startPoll();
            }
        }

    }

    void unsubscribe(int index) {
        boolean wasActive = isActive();
        subscribed[index] = false;
        if (wasActive && !isActive()) {
            if (covSub) stopCov();
            else stopPoll();
        }


    }

    boolean isActive() {
        for (boolean b : subscribed) {
            if (b) return true;
        }
        return false;
    }

    void startPoll() {
        folder.root.addPointSub(this);
        LOGGER.debug("started polling for " + getObjectName());
    }

    void stopPoll() {
        folder.root.removePointSub(this);
        LOGGER.debug("cancelled polling for " + getObjectName());
    }

    void startCov() {
        ScheduledThreadPoolExecutor stpe = folder.root.getDaemonThreadPool();

        stpe.schedule(new Runnable() {
            public void run() {
                getPoint(getMe(), folder);
            }
        }, 0, TimeUnit.SECONDS);
        folder.setupCov(this, listener);
        final ArrayBlockingQueue<SequenceOf<PropertyValue>> covEv = listener.event;
        if (folder.conn.link.futures.containsKey(this)) {
            return;
        }

        ScheduledFuture<?> fut = stpe.scheduleWithFixedDelay(new Runnable() {
            public void run() {
                SequenceOf<PropertyValue> listOfValues = covEv.poll();
                if (listOfValues != null) {
                    for (PropertyValue pv : listOfValues) {
                        if (node != null) LOGGER.debug("got cov for " + node.getName());
                        folder.updatePointValue(getMe(), pv.getPropertyIdentifier(), pv.getValue());
                    }
                }
            }
        }, 0, 50, TimeUnit.MILLISECONDS);
        folder.conn.link.futures.put(this, fut);
    }

    void stopCov() {
        ScheduledFuture<?> fut = folder.conn.link.futures.remove(this);
        if (fut != null) {
            fut.cancel(false);
        }
        //cl.event.active = false;
        if (folder.conn.localDevice != null)
            folder.conn.localDevice.getEventHandler().removeListener(listener);
    }


    private static void getPoint(BacnetPoint point, DeviceFolder devicefold) {
        PropertyReferences refs = new PropertyReferences();
        Map<ObjectIdentifier, BacnetPoint> points = new HashMap<ObjectIdentifier, BacnetPoint>();
        ObjectIdentifier oid = point.oid;
        DeviceFolder.addPropertyReferences(refs, oid);
        points.put(oid, point);
        devicefold.getProperties(refs, points);
    }
    
    private class Db extends Database {

		public Db() {
			super(node.getName(), null);
		}

		@Override
		public void write(String path, Value value, long ts) {
			throw new UnsupportedOperationException();
			
		}

		@Override
		public void query(String path, long from, long to, CompleteHandler<QueryData> handler) {
			DateTime start = new DateTime(from);
//			LOGGER.info("start time: " + Utils.datetimeToString(start));
			ByTime bytime = new ByTime(start, new SignedInteger(bufferSize));
			ReadRangeRequest request = new ReadRangeRequest(oid, PropertyIdentifier.logBuffer, null, bytime);
			try {
				ReadRangeAck response = (ReadRangeAck) folder.conn.localDevice.send(folder.root.device, request).get();
				for (LogRecord record: (SequenceOf<LogRecord>) response.getItemData()) {
					long ts = record.getTimestamp().getGC().getTimeInMillis();
					if (ts > to) continue;
					Encodable enc = record.getEncodable();
					Value v;
					if (enc instanceof com.serotonin.bacnet4j.type.primitive.Boolean) {
						v = new Value(((com.serotonin.bacnet4j.type.primitive.Boolean) enc).booleanValue());
					} else if (enc instanceof Real) {
						v = new Value(((Real) enc).floatValue());
					} else if (enc instanceof UnsignedInteger) {
						v = new Value(((UnsignedInteger) enc).bigIntegerValue());
					} else if (enc instanceof SignedInteger) {
						v = new Value(((SignedInteger) enc).bigIntegerValue());
					} else {
						v = new Value(enc.toString());
					}
					QueryData qd = new QueryData(v, record.getTimestamp().getGC().getTimeInMillis());
					handler.handle(qd);
				}
			} catch (BACnetException e) {
				LOGGER.debug("", e);
			} finally {
				handler.complete();
			}
		}

		@Override
		public QueryData queryFirst(String path) {
			throw new UnsupportedOperationException();
		}

		@Override
		public QueryData queryLast(String path) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void close() throws Exception {
			throw new UnsupportedOperationException();
			
		}

		@Override
		protected void performConnect() throws Exception {
			throw new UnsupportedOperationException();
			
		}

		@Override
		public void initExtensions(Node node) {
			throw new UnsupportedOperationException();
			
		}
    	
    }
}

