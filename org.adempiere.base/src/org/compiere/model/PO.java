/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution                       *
 * Copyright (C) 1999-2006 ComPiere, Inc. All Rights Reserved.                *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program; if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 * For the text or an alternative of this public license, you may reach us    *
 * ComPiere, Inc., 2620 Augustine Dr. #245, Santa Clara, CA 95054, USA        *
 * or via info@compiere.org or http://www.compiere.org/license.html           *
 *****************************************************************************/
package org.compiere.model;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Collectors;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.adempiere.base.event.EventManager;
import org.adempiere.base.event.IEventTopics;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.exceptions.CrossTenantException;
import org.adempiere.exceptions.DBException;
import org.adempiere.process.UUIDGenerator;
import org.compiere.Adempiere;
import org.compiere.acct.Doc;
import org.compiere.db.AdempiereDatabase;
import org.compiere.db.Database;
import org.compiere.util.AdempiereUserError;
import org.compiere.util.CCache;
import org.compiere.util.CLogMgt;
import org.compiere.util.CLogger;
import org.compiere.util.CacheMgt;
import org.compiere.util.DB;
import org.compiere.util.DisplayType;
import org.compiere.util.Env;
import org.compiere.util.Evaluatee;
import org.compiere.util.Language;
import org.compiere.util.Msg;
import org.compiere.util.SecureEngine;
import org.compiere.util.Trace;
import org.compiere.util.Trx;
import org.compiere.util.TrxEventListener;
import org.compiere.util.Util;
import org.compiere.util.ValueNamePair;
import org.osgi.service.event.Event;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 *  Abstract base class for Persistent Object.
 *
 *  @author Jorg Janke
 *  @version $Id: PO.java,v 1.12 2006/08/09 16:38:47 jjanke Exp $
 *
 *  @author Teo Sarca, SC ARHIPAC SERVICE SRL
 *			<li>FR [ 1675490 ] ModelValidator on modelChange after events
 *			<li>BF [ 1704828 ] PO.is_Changed() and PO.is_ValueChanged are not consistent
 *			<li>FR [ 1720995 ] Add PO.saveEx() and PO.deleteEx() methods
 *			<li>BF [ 1990856 ] PO.set_Value* : truncate string more than needed
 *			<li>FR [ 2042844 ] PO.get_Translation improvements
 *			<li>FR [ 2818369 ] Implement PO.get_ValueAs*(columnName)
 *				https://sourceforge.net/p/adempiere/feature-requests/754/
 *			<li>BF [ 2849122 ] PO.AfterSave is not rollback on error
 *				https://sourceforge.net/p/adempiere/bugs/2073/
 *			<li>BF [ 2859125 ] Can't set AD_OrgBP_ID
 *				https://sourceforge.net/p/adempiere/bugs/2095/
 *			<li>BF [ 2866493 ] VTreePanel is not saving who did the node move
 *				https://sourceforge.net/p/adempiere/bugs/2135/
 * @author Teo Sarca, teo.sarca@gmail.com
 * 			<li>BF [ 2876259 ] PO.insertTranslation query is not correct
 * 				https://sourceforge.net/p/adempiere/bugs/2168/
 * @author Victor Perez, e-Evolution SC
 *			<li>[ 2195894 ] Improve performance in PO engine
 *			<li>https://sourceforge.net/p/adempiere/feature-requests/555/
 *			<li>BF [2947622] The replication ID (Primary Key) is not working
 *			<li>https://sourceforge.net/p/adempiere/bugs/2308/
 */
public abstract class PO
	implements Serializable, Comparator<Object>, Evaluatee, Cloneable
{
    /**
	 * 
	 */
	private static final long serialVersionUID = -2193260381693906628L;

	/** String key to create a new record based in UUID constructor */
	public static final String UUID_NEW_RECORD = "";

	public static final String LOCAL_TRX_PREFIX = "POSave";

	/** default query/statement timeout, 300 seconds **/
	private static final int QUERY_TIME_OUT = 300;

	/** Get value of the attribute for Table ID and Record ID **/
	private static final String TABLE_ATTRIBUTE_VALUE_SQL = """
			SELECT a.Name, a.AttributeValueType, a.AD_Reference_ID, ta.Value, ta.ValueDate, ta.ValueNumber, ta.M_AttributeValue_ID
				FROM AD_TableAttribute ta
				INNER JOIN M_Attribute a ON (a.M_Attribute_ID = ta.M_Attribute_ID)
				WHERE ta.AD_Table_ID = ? AND Record_ID = ? AND a.IsActive = 'Y' """;

	/** Record Attribute and Value Map */
	private Map<String, Object> m_tableAttributeMap = new HashMap<String, Object>();

	/**
	 * 	Set Document Value Workflow Manager
	 *	@param docWFMgr mgr
	 */
	public static void setDocWorkflowMgr (DocWorkflowMgr docWFMgr)
	{
		s_docWFMgr = docWFMgr;
		s_log.config (s_docWFMgr.toString());
	}	//	setDocWorkflowMgr

	/** Document Value Workflow Manager		*/
	private static DocWorkflowMgr		s_docWFMgr = null;

	/** User Maintained Entity Type				*/
	static public final String ENTITYTYPE_UserMaintained = "U";
	/** Dictionary Maintained Entity Type		*/
	static public final String ENTITYTYPE_Dictionary = "D";

	/**
	 *  Create New Persistent Object
	 *  @param ctx context
	 */
	public PO (Properties ctx)
	{
		this (ctx, 0, null, null, (String[]) null);
	}   //  PO

	/**
	 *  Create or Load existing Persistent Object
	 *  @param ctx context
	 *  @param ID The unique ID of the object
	 *  @param trxName transaction name
	 */
	public PO (Properties ctx, int ID, String trxName)
	{
		this (ctx, ID, trxName, null, (String[]) null);
	}   //  PO

	/**
	 *  Create or Load existing Persistent Object
	 *  @param ctx context
	 *  @param UUID The unique UUID of the object
	 *  @param trxName transaction name
	 */
	public PO (Properties ctx, String UUID, String trxName)
	{
		this (ctx, UUID, trxName, null, (String[]) null);
	}   //  PO

	/**
	 * Create or load existing Persistent Object
	 * @param ctx Context
	 * @param ID Unique ID of the object
	 * @param trxName Transaction name
	 * @param virtualColumns names of virtual columns to load along with the regular table columns
	 */
	public PO (Properties ctx, int ID, String trxName, String ... virtualColumns)
	{
		this (ctx, ID, trxName, null, virtualColumns);
	}

	/**
	 * Create or load existing Persistent Object
	 * @param ctx Context
	 * @param UUID Unique UUID of the object
	 * @param trxName Transaction name
	 * @param virtualColumns names of virtual columns to load along with the regular table columns
	 */
	public PO (Properties ctx, String UUID, String trxName, String ... virtualColumns)
	{
		this (ctx, UUID, trxName, null, virtualColumns);
	}

	/**
	 *  Create or Load existing Persistent Object.
	 *  @param ctx context
	 *  @param rs optional - load from current result set position. If null, a new record is created.
	 *  @param trxName transaction name
	 */
	public PO (Properties ctx, ResultSet rs, String trxName)
	{
		this (ctx, 0, trxName, rs);
	}	//	PO

	/**
	 *  Create or Load existing Persistent Object.
	 *  <pre>
	 *  You load
	 *    - an existing single key record with   new PO (ctx, Record_ID)
	 *           or                              new PO (ctx, Record_ID, trxName)
	 *           or                              new PO (ctx, rs, trxName)
	 *    - a new single key record with         new PO (ctx, 0)
	 *    - an existing multi key record with    new PO (ctx, rs, trxName)
	 *    - a new multi key record with          new PO (ctx, null)
	 *  The ID for new single key records is created automatically,
	 *  you need to set the IDs for multi-key records explicitly.
	 *	</pre>
	 *  @param ctx context
	 *  @param ID the ID or 0 to create new record. Ignore if rs is not null.
	 *  @param trxName transaction name
	 *  @param rs optional - load from current result set position
	 *  @param virtualColumns optional - names of virtual columns to load along with the regular table columns
	 */
	public PO (Properties ctx, int ID, String trxName, ResultSet rs, String ... virtualColumns)
	{
		p_ctx = ctx != null ? ctx : Env.getCtx();
		m_trxName = trxName;

		p_info = initPO(ctx);
		if (p_info == null || p_info.getTableName() == null)
			throw new IllegalArgumentException ("Invalid PO Info - " + p_info);
		//
		int size = p_info.getColumnCount();
		m_oldValues = new Object[size];
		m_newValues = new Object[size];
		m_setErrors = new ValueNamePair[size];
		m_setErrorsFilled = false;

		if (rs != null)
			load(rs);
		else
			load(ID, trxName, virtualColumns);

		checkCrossTenant(false);
	}   //  PO

	/**
	 *  Create or Load existing Persistent Object.
	 *  <pre>
	 *  You load an existing record with       new PO (ctx, UUID)
	 *        or                               new PO (ctx, UUID, trxName)
	 *        or                               new PO (ctx, rs, trxName)
	 *  The UUID for new records is created automatically,
	 *  you need to set the IDs for multi-key records explicitly.
	 *	</pre>
	 *  @param ctx context
	 *  @param UUID the UUID or "" to create new record. Ignore if rs is not null.
	 *  @param trxName transaction name
	 *  @param rs optional - load from current result set position
	 *  @param virtualColumns optional - names of virtual columns to load along with the regular table columns
	 */
	public PO (Properties ctx, String UUID, String trxName, ResultSet rs, String ... virtualColumns)
	{
		p_ctx = ctx != null ? ctx : Env.getCtx();
		m_trxName = trxName;

		p_info = initPO(ctx);
		if (p_info == null || p_info.getTableName() == null)
			throw new IllegalArgumentException ("Invalid PO Info - " + p_info);
		//
		int size = p_info.getColumnCount();
		m_oldValues = new Object[size];
		m_newValues = new Object[size];
		m_setErrors = new ValueNamePair[size];
		m_setErrorsFilled = false;

		if (rs != null)
		{
			load(rs);
		}
		else
		{
			if (UUID != null && UUID.length() == 0) //	new
			{
				initNewRecord();
			}
			else
			{
				loadPO(UUID, trxName, virtualColumns);
			}
		}

		checkCrossTenant(false);
	}   //  PO

	/**
	 * 	Create New PO by Copying existing (key not copied).
	 * 	@param ctx context
	 * 	@param source source object
	 * 	@param AD_Client_ID client
	 * 	@param AD_Org_ID org
	 */
	public PO (Properties ctx, PO source, int AD_Client_ID, int AD_Org_ID)
	{
		this (ctx, 0, null, (String[]) null);	//	create new
		//
		if (source != null)
			copyValues (source, this);
		setAD_Client_ID(AD_Client_ID);
		setAD_Org_ID(AD_Org_ID);
	}	//	PO

	/**
	 * Copy all properties from copy. Method to help the implementation of copy constructor.
	 * @param copy
	 */
	protected void copyPO(PO copy)
	{
		this.m_attachment = copy.m_attachment != null ? new MAttachment(copy.m_attachment) : null;
		this.m_attributes = copy.m_attributes != null ? new HashMap<String, Object>(copy.m_attributes) : null;
		this.m_tableAttributeMap = copy.m_tableAttributeMap != null ? new HashMap<String, Object>(copy.m_tableAttributeMap) : null;
		this.m_createNew = copy.m_createNew;
		this.m_custom = copy.m_custom != null ? new HashMap<String, String>(copy.m_custom) : null;
		this.m_IDs = copy.m_IDs != null ? Arrays.copyOf(copy.m_IDs, copy.m_IDs.length) : null;
		this.m_KeyColumns = copy.m_KeyColumns != null ? Arrays.copyOf(copy.m_KeyColumns, copy.m_KeyColumns.length) : null;
		this.m_lobInfo = copy.m_lobInfo != null ? copy.m_lobInfo.stream().map(PO_LOB::new).collect(Collectors.toCollection(ArrayList::new)) : null;
		this.m_newValues = copy.m_newValues != null ? Arrays.copyOf(copy.m_newValues, copy.m_newValues.length) : null;
		this.m_oldValues = copy.m_oldValues != null ? Arrays.copyOf(copy.m_oldValues, copy.m_oldValues.length) : null;		
		this.s_acctColumns = copy.s_acctColumns != null ? copy.s_acctColumns.stream().collect(Collectors.toCollection(ArrayList::new)) : null;
	}
	
	/**	Logger							*/
	protected transient CLogger	log = CLogger.getCLogger (getClass());
	/** Static Logger					*/
	private static CLogger		s_log = CLogger.getCLogger (PO.class);

	/** Context                 */
	protected transient Properties		p_ctx;
	/** Model Info              */
	protected transient volatile POInfo	p_info = null;

	/** Original Values         */
	private Object[]    		m_oldValues = null;
	/** New Values              */
	private Object[]    		m_newValues = null;
	/** Errors when setting     */
	private ValueNamePair[]		m_setErrors = null;
	private boolean				m_setErrorsFilled = false;  // to optimize not traveling the array if no errors

	/** Record_IDs          		*/
	private Object[]       		m_IDs = new Object[] {I_ZERO};
	/** Key Columns					*/
	private String[]         	m_KeyColumns = null;
	/** Create New for Multi Key 	*/
	private boolean				m_createNew = false;
	/**	Attachment with entries	*/
	private MAttachment			m_attachment = null;
	/**	Deleted ID					*/
	private int					m_idOld = 0;
	/** Custom Columns 				*/
	private HashMap<String,String>	m_custom = null;
	/** Attributes	 				*/
	private HashMap<String,Object>	m_attributes = null;

	/** Zero Integer				*/
	protected static final Integer I_ZERO = Integer.valueOf(0);
	/** Accounting Columns			*/
	private ArrayList <String>	s_acctColumns = null;

	/** Trifon - Indicates that this record is created by replication functionality.*/
	private boolean m_isReplication = false;
	
	/** Immutable flag **/
	private boolean m_isImmutable = false;
	
	private String[] m_optimisticLockingColumns = new String[] {"Updated"};
	private Boolean m_useOptimisticLocking = null;

	/** Indices of virtual columns that were already resolved */
	private Set<Integer> loadedVirtualColumns = new HashSet<>();

	/** Access Level S__ 100	4	System info			*/
	public static final int ACCESSLEVEL_SYSTEM = 4;
	/** Access Level _C_ 010	2	Client info			*/
	public static final int ACCESSLEVEL_CLIENT = 2;
	/** Access Level __O 001	1	Organization info	*/
	public static final int ACCESSLEVEL_ORG = 1;
	/**	Access Level SCO 111	7	System shared info	*/
	public static final int ACCESSLEVEL_ALL = 7;
	/** Access Level SC_ 110	6	System/Client info	*/
	public static final int ACCESSLEVEL_SYSTEMCLIENT = 6;
	/** Access Level _CO 011	3	Client shared info	*/
	public static final int ACCESSLEVEL_CLIENTORG = 3;

	/**
	 *  Initialize and return POInfo
	 *  @param ctx context
	 *  @return Meta data of PO
	 */
	abstract protected POInfo initPO (Properties ctx);

	/**
	 * 	Get Table Access Level
	 *	@return Access Level
	 */
	abstract protected int get_AccessLevel();

	/**
	 *  String representation
	 *  @return String representation
	 */
	@Override
	public String toString()
	{
		StringBuilder sb = new StringBuilder("PO[")
			.append(get_WhereClause(true)).append("]");
		return sb.toString();
	}	//  toString

	/**
	 * 	Equals based on ID
	 * 	@param cmp comparator
	 * 	@return true if ID the same
	 */
	@Override
	public boolean equals (Object cmp)
	{
		if (cmp == null)
			return false;
		if (!(cmp instanceof PO))
			return false;
		if (cmp.getClass().equals(this.getClass()))
			// if both ID's are zero they can't be compared by ID
			if (((PO)cmp).get_ID() == 0 && get_ID() == 0)
				return super.equals(cmp);
			else
				return ((PO)cmp).get_ID() == get_ID();
		return super.equals(cmp);
	}	//	equals
	
	@Override
	public int hashCode()
	{
	  return 42; // any arbitrary constant will do
	}

	/**
	 * 	Compare based on DocumentNo, Value, Name, Description
	 *	@param o1 Object 1
	 *	@param o2 Object 2
	 *	@return -1 if o1 &lt; o2
	 */
	@Override
	public int compare (Object o1, Object o2)
	{
		if (o1 == null)
			return -1;
		else if (o2 == null)
			return 1;
		if (!(o1 instanceof PO))
			throw new ClassCastException ("Not PO -1- " + o1);
		if (!(o2 instanceof PO))
			throw new ClassCastException ("Not PO -2- " + o2);
		//	same class
		Collator collator = Collator.getInstance();
		if (o1.getClass().equals(o2.getClass()))
		{
			int index = get_ColumnIndex("DocumentNo");
			if (index == -1)
				index = get_ColumnIndex("Value");
			if (index == -1)
				index = get_ColumnIndex("Name");
			if (index == -1)
				index = get_ColumnIndex("Description");
			if (index != -1)
			{
				PO po1 = (PO)o1;
				Object comp1 = po1.get_Value(index);
				PO po2 = (PO)o2;
				Object comp2 = po2.get_Value(index);
				if (comp1 == null)
					return -1;
				else if (comp2 == null)
					return 1;
				return collator.compare(comp1.toString(), comp2.toString());
			}
		}
		return collator.compare(o1.toString(), o2.toString());
	}	//	compare

	/**
	 *  Get TableName.
	 *  @return table name
	 */
	public String get_TableName()
	{
		return p_info.getTableName();
	}   //  get_TableName

	/**
	 *  Get Key Columns.
	 *  @return table name
	 */
	public String[] get_KeyColumns()
	{
		return m_KeyColumns;
	}   //  get_KeyColumns

	/**
	 *  Get Table ID.
	 *  @return table id
	 */
	public int get_Table_ID()
	{
		return p_info.getAD_Table_ID();
	}   //  get_TableID

	/**
	 *  Get Single Key Record ID
	 *  @return ID or 0
	 */
	public int get_ID()
	{
		Object oo = m_IDs[0];
		if (oo != null && oo instanceof Integer)
			return ((Integer)oo).intValue();
		return 0;
	}   //  getID

	/**
	 *  Get old Single Key Record ID
	 *  @return ID or 0
	 */
	public int get_IDOld()
	{
		return m_idOld;
	}   //  getID

	/**
	 * Get UUID
	 * @return UUID value
	 */
	public String get_UUID() {
		String uidColumn = getUUIDColumnName();
		if (p_info.getColumnIndex(uidColumn) >=0)
			return get_ValueAsString(uidColumn);
		else
			return null;
	}
	
	/**
	 * 	Get Context
	 * 	@return context
	 */
	public Properties getCtx()
	{
		return p_ctx;
	}	//	getCtx

	/**
	 * 	Get Logger
	 *	@return logger
	 */
	public CLogger get_Logger()
	{
		return log;
	}	//	getLogger

	/**
	 *  Get Value
	 *  @param index column index
	 *  @return column value
	 */
	public final Object get_Value (int index)
	{
		if (index < 0 || index >= get_ColumnCount())
		{
			log.log(Level.WARNING, "Index invalid - " + index);
			return null;
		}
		if (m_newValues[index] != null)
		{
			if (m_newValues[index].equals(Null.NULL))
				return null;
			return m_newValues[index];
		}
		if(p_info.isVirtualColumn(index) && p_info.isVirtualDBColumn(index))
			loadVirtualColumn(index);
		return m_oldValues[index];
	}   //  get_Value

	/**
	 *  Get Value as int
	 *  @param index column index
	 *  @return int value or 0
	 */
	public int get_ValueAsInt (int index)
	{
		Object value = get_Value(index);
		if (value == null)
			return 0;
		if (value instanceof Integer)
			return ((Integer)value).intValue();
		try
		{
			return Integer.parseInt(value.toString());
		}
		catch (NumberFormatException ex)
		{
			log.warning(p_info.getColumnName(index) + " - " + ex.getMessage());
			return 0;
		}
	}   //  get_ValueAsInt

	/**
	 *  Get Value
	 *  @param columnName column name
	 *  @return value or null
	 */
	public final Object get_Value (String columnName)
	{
		int index = get_ColumnIndex(columnName);
		if (index < 0)
		{
			log.log(Level.WARNING, "Column not found - " + get_TableName() + "." + columnName);
			Trace.printStack();
			return null;
		}
		return get_Value (index);
	}   //  get_Value

	/**
	 *  Get Encrypted Value
	 *  @param columnName column name
	 *  @return value or null
	 */
	protected final Object get_ValueE (String columnName)
	{
		return get_Value (columnName);
	}   //  get_ValueE

	/**
	 * Get String Value
	 * @param columnName
	 * @return String value
	 */
	@Override
	public String get_ValueAsString(String columnName)
	{
		int idx = get_ColumnIndex(columnName);
		if (idx < 0)
			return "";
		return get_ValueAsString(idx);
	}

	/**
	 * 	Get String Value
	 *	@param idx column index
	 *	@return String value or ""
	 */
	public String get_ValueAsString(int idx)
	{
		Object value = get_Value(idx);
		if (value == null)
			return "";
		return value.toString();
	}	//	get_ValueAsString

	/**
	 *  Get Value
	 *  @param AD_Column_ID column id
	 *  @return value or null
	 */
	public final Object get_ValueOfColumn (int AD_Column_ID)
	{
		int index = p_info.getColumnIndex(AD_Column_ID);
		if (index < 0)
		{
			log.log(Level.WARNING, "Not found - AD_Column_ID=" + AD_Column_ID);
			return null;
		}
		return get_Value (index);
	}   //  get_ValueOfColumn

	/**
	 *  Get Old Value
	 *  @param index column index
	 *  @return old value
	 */
	public final Object get_ValueOld (int index)
	{
		if (index < 0 || index >= get_ColumnCount())
		{
			log.log(Level.WARNING, "Index invalid - " + index);
			return null;
		}
		return m_oldValues[index];
	}   //  get_ValueOld

	/**
	 *  Get Old Value
	 *  @param columnName column name
	 *  @return old value or null
	 */
	public final Object get_ValueOld (String columnName)
	{
		int index = get_ColumnIndex(columnName);
		if (index < 0)
		{
			log.log(Level.WARNING, "Column not found - " + get_TableName() + "." + columnName);
			return null;
		}
		return get_ValueOld (index);
	}   //  get_ValueOld

	/**
	 *  Get Old Value as int
	 *  @param columnName column name
	 *  @return int value or 0
	 */
	public int get_ValueOldAsInt (String columnName)
	{
		Object value = get_ValueOld(columnName);
		if (value == null)
			return 0;
		if (value instanceof Integer)
			return ((Integer)value).intValue();
		try
		{
			return Integer.parseInt(value.toString());
		}
		catch (NumberFormatException ex)
		{
			log.warning(columnName + " - " + ex.getMessage());
			return 0;
		}
	}   //  get_ValueOldAsInt

	/**
	 *  Is Value Changed
	 *  @param index column index
	 *  @return true if changed
	 */
	public final boolean is_ValueChanged (int index)
	{
		if (index < 0 || index >= get_ColumnCount())
		{
			log.log(Level.WARNING, "Index invalid - " + index);
			return false;
		}
		if (m_newValues[index] == null)
			return false;
		if (m_newValues[index] == Null.NULL && m_oldValues[index] == null)
			return false;
		return !m_newValues[index].equals(m_oldValues[index]);
	}   //  is_ValueChanged

	/**
	 *  Is Value Changed
	 *  @param columnName column name
	 *  @return true if changed
	 */
	public final boolean is_ValueChanged (String columnName)
	{
		int index = get_ColumnIndex(columnName);
		if (index < 0)
		{
			log.log(Level.WARNING, "Column not found - " + get_TableName() + "." + columnName);
			return false;
		}
		return is_ValueChanged (index);
	}   //  is_ValueChanged

	/**
	 *  Get new - old.<br/>
	 * 	- New Value if Old Value is null<br/>
	 * 	- New Value - Old Value if Number<br/>
	 * 	- otherwise null
	 *  @param index index
	 *  @return new - old or null if not appropriate or not changed
	 */
	public final Object get_ValueDifference (int index)
	{
		if (index < 0 || index >= get_ColumnCount())
		{
			log.log(Level.WARNING, "Index invalid - " + index);
			return null;
		}
		Object nValue = m_newValues[index];
		//	No new Value or NULL
		if (nValue == null || nValue == Null.NULL)
			return null;
		//
		Object oValue = m_oldValues[index];
		if (oValue == null || oValue == Null.NULL)
			return nValue;
		if (nValue instanceof BigDecimal)
		{
			BigDecimal obd = (BigDecimal)oValue;
			return ((BigDecimal)nValue).subtract(obd);
		}
		else if (nValue instanceof Integer)
		{
			int result = ((Integer)nValue).intValue();
			result -= ((Integer)oValue).intValue();
			return Integer.valueOf(result);
		}
		//
		log.warning("Invalid type - New=" + nValue);
		return null;
	}   //  get_ValueDifference

	/**
	 *  Get new - old.<br/>
	 * 	- New Value if Old Value is null<br/>
	 * 	- New Value - Old Value if Number<br/>
	 * 	- otherwise null
	 *  @param columnName column name
	 *  @return new - old or null if not appropriate or not changed
	 */
	public final Object get_ValueDifference (String columnName)
	{
		int index = get_ColumnIndex(columnName);
		if (index < 0)
		{
			log.log(Level.WARNING, "Column not found - " + get_TableName() + "." + columnName);
			return null;
		}
		return get_ValueDifference (index);
	}   //  get_ValueDifference

	/**
	 *  Set Value
	 *  @param ColumnName column name
	 *  @param value value to set
	 *  @return true if value set
	 */
	protected final boolean set_Value (String ColumnName, Object value)
	{
		return set_Value(ColumnName, value, true);
	}
	
	/**
	 *  Set Value
	 *  @param ColumnName column name
	 *  @param value value to set
	 *  @param checkWritable true to check is column writable
	 *  @return true if value set
	 */
	protected final boolean set_Value (String ColumnName, Object value, boolean checkWritable)
	{
		checkImmutable();
		
		if (value instanceof String && ColumnName.equals("WhereClause")
			&& value.toString().toUpperCase().indexOf("=NULL") != -1)
			log.warning("Invalid Null Value - " + ColumnName + "=" + value);

		int index = get_ColumnIndex(ColumnName);
		if (index < 0)
		{
			log.log(Level.SEVERE, "Column not found - " + get_TableName() + "." + ColumnName);
			log.saveError("ColumnNotFound", get_TableName() + "." + ColumnName);
			return false;
		}
		if (ColumnName.endsWith("_ID") && value instanceof String )
		{
			// Convert to Integer only if info class is Integer - teo_sarca [ 2859125 ]
			Class<?> clazz = p_info.getColumnClass(p_info.getColumnIndex(ColumnName));
			if (Integer.class == clazz)
			{
				log.severe("Invalid Data Type for " + ColumnName + "=" + value);
				value = Integer.parseInt((String)value);
			}
		}

		return set_Value (index, value, checkWritable);
	}   //  setValue

	/**
	 *  Set Encrypted Value
	 *  @param ColumnName column name
	 *  @param value value
	 *  @return true if value set
	 */
	protected final boolean set_ValueE (String ColumnName, Object value)
	{
		return set_Value (ColumnName, value);
	}   //  setValueE

	/**
	 *  Set Value if updateable and correct class.
	 *  (and to NULL if not mandatory)
	 *  @param index column index
	 *  @param value value to set
	 *  @return true if value set
	 */
	protected final boolean set_Value (int index, Object value)
	{
		return set_Value(index, value, true);
	}
	
	/**
	 *  Set Value if updateable and correct class.
	 *  (and to NULL if not mandatory)
	 *  @param index column index
	 *  @param value value to set
	 *  @param checkWritable
	 *  @return true if value set
	 */
	protected final boolean set_Value (int index, Object value, boolean checkWritable)
	{
		checkImmutable();
		
		if (index < 0 || index >= get_ColumnCount())
		{
			log.log(Level.WARNING, "Index invalid - " + index);
			return false;
		}
		String ColumnName = p_info.getColumnName(index);
		String colInfo = " - " + ColumnName;
		//
		m_setErrors[index] = null;
		if (checkWritable)
		{
			if (p_info.isVirtualColumn(index))
			{
				log.log(Level.WARNING, "Virtual Column" + colInfo);
				log.saveError("VirtualColumn", "Virtual Column" + colInfo);
				m_setErrors[index] = new ValueNamePair("VirtualColumn", "Virtual Column" + colInfo);
				m_setErrorsFilled = true;
				return false;
			}
	
			//
			// globalqss -- Bug 1618469 - is throwing not updateable even on new records
			if ( ( ! p_info.isColumnUpdateable(index) ) && ( ! is_new() ) )
			{
				colInfo += " - NewValue=" + value + " - OldValue=" + get_Value(index);
				log.log(Level.WARNING, "Column not updateable" + colInfo);
				log.saveError("ColumnReadonly", "Column not updateable" + colInfo);
				m_setErrors[index] = new ValueNamePair("ColumnReadonly", "Column not updateable" + colInfo);
				m_setErrorsFilled = true;
				return false;
			}
		}
		//
		if (value == null)
		{
			if (checkWritable && p_info.isColumnMandatory(index))
			{
				log.saveError("FillMandatory", ColumnName);
				m_setErrors[index] = new ValueNamePair("FillMandatory", ColumnName);
				m_setErrorsFilled = true;
				return false;
			}
			m_newValues[index] = Null.NULL;          //  correct
			if (log.isLoggable(Level.FINER)) log.finer(ColumnName + " = null");
		}
		else
		{
			//  matching class or generic object
			if (value.getClass().equals(p_info.getColumnClass(index))
				|| p_info.getColumnClass(index) == Object.class)
				m_newValues[index] = value;     //  correct
			//  Integer can be set as BigDecimal
			else if (value.getClass() == BigDecimal.class
				&& p_info.getColumnClass(index) == Integer.class)
				m_newValues[index] = Integer.valueOf(((BigDecimal)value).intValue());
			//	Set Boolean
			else if (p_info.getColumnClass(index) == Boolean.class
				&& ("Y".equals(value) || "N".equals(value)) )
				m_newValues[index] = Boolean.valueOf("Y".equals(value));
			// added by vpj-cd
			// To solve BUG [ 1618423 ] Set Project Type button in Project window throws warning
			// generated because C_Project.C_Project_Type_ID is defined as button in dictionary
			// although is ID (integer) in database
			else if (value.getClass() == Integer.class
					&& p_info.getColumnClass(index) == String.class)
					m_newValues[index] = value;
			else if (value.getClass() == String.class
					&& p_info.getColumnClass(index) == Integer.class)
				try
				{
					m_newValues[index] = Integer.valueOf((String)value);
				}
				catch (NumberFormatException e)
				{
					String errmsg = ColumnName
							+ " - Class invalid: " + value.getClass().toString()
							+ ", Should be " + p_info.getColumnClass(index).toString() + ": " + value;
					log.log(Level.SEVERE, errmsg);
					log.saveError("WrongDataType", errmsg);
					m_setErrors[index] = new ValueNamePair("WrongDataType", errmsg);
					m_setErrorsFilled = true;
					return false;
				}
			else
			{
				String errmsg = ColumnName
						+ " - Class invalid: " + value.getClass().toString()
						+ ", Should be " + p_info.getColumnClass(index).toString() + ": " + value;
				log.log(Level.SEVERE, errmsg);
				log.saveError("WrongDataType", errmsg);
				m_setErrors[index] = new ValueNamePair("WrongDataType", errmsg);
				m_setErrorsFilled = true;
				return false;
			}
			//	Validate (Min/Max)
			String error = p_info.validate(index, value);
			if (error != null)
			{
				log.log(Level.WARNING, ColumnName + "=" + value + " - " + error);
				int separatorIndex = error.indexOf(";");
				if (separatorIndex > 0) {
					log.saveError(error.substring(0,separatorIndex), error.substring(separatorIndex+1));
					m_setErrors[index] = new ValueNamePair(error.substring(0,separatorIndex), error.substring(separatorIndex+1));
				} else {
					log.saveError(error, ColumnName);
					m_setErrors[index] = new ValueNamePair(error, ColumnName);
				}
				m_setErrorsFilled = true;
				return false;
			}
			//	Length for String
			if (p_info.getColumnClass(index) == String.class)
			{
				String stringValue = value.toString();
				int length = p_info.getFieldLength(index);
				if (stringValue.length() > length && length > 0)
				{
					log.warning(ColumnName + " - Value too long - truncated to length=" + length);
					m_newValues[index] = stringValue.substring(0,length);
				}
			}
			// Validate reference list [1762461]
			if (p_info.getColumn(index).DisplayType == DisplayType.List &&
				p_info.getColumn(index).AD_Reference_Value_ID > 0 &&
				value instanceof String) {
				if (MRefList.get(getCtx(), p_info.getColumn(index).AD_Reference_Value_ID,
						(String) value, get_TrxName()) != null)
					;
				else {
					StringBuilder validValues = new StringBuilder();
					for (ValueNamePair vp : MRefList.getList(getCtx(), p_info.getColumn(index).AD_Reference_Value_ID, false))
						validValues.append(" - ").append(vp.getValue());
					String errmsg = ColumnName + " Invalid value - "
							+ value + " - Reference_ID=" + p_info.getColumn(index).AD_Reference_Value_ID + validValues.toString();
					log.saveError("Validate", errmsg);
					m_setErrors[index] = new ValueNamePair("Validate", errmsg);
					m_setErrorsFilled = true;
					return false;
				}
			}
			if (log.isLoggable(Level.FINEST)) log.finest(ColumnName + " = " + m_newValues[index] + " (OldValue="+m_oldValues[index]+")");
		}
		set_Keys (ColumnName, m_newValues[index]);

		// FR 2962094 Fill ProcessedOn when the Processed column is changing from N to Y
		setProcessedOn(ColumnName, value, m_oldValues[index]);

		return true;
	}   //  setValue

	/**
	 * FR 2962094 - Finish implementation of weighted average costing. <br/>
	 * Fill the column ProcessedOn (if it exists) with a bigdecimal representation of current timestamp (with nanoseconds).
	 * @param ColumnName update ProcessedOn if ColumnName is Processed
	 * @param value new value of Processed column
	 * @param oldValue old value of Processed column
	 */
	public void setProcessedOn(String ColumnName, Object value, Object oldValue) {
		checkImmutable();
		
		if ("Processed".equals(ColumnName)
				&& value instanceof Boolean
				&& ((Boolean)value).booleanValue() == true
				&& (oldValue == null
				    || (oldValue instanceof Boolean
				        && ((Boolean)oldValue).booleanValue() == false))) {
			if (get_ColumnIndex("ProcessedOn") > 0) {
				// fill processed on column
				//get current time from db
				Timestamp ts = DB.getSQLValueTS(null, "SELECT CURRENT_TIMESTAMP FROM DUAL");
				long mili = ts.getTime();
				int nano = ts.getNanos();
				double doublets = Double.parseDouble(Long.toString(mili) + "." + Integer.toString(nano));
				BigDecimal bdtimestamp = BigDecimal.valueOf(doublets);
				set_Value("ProcessedOn", bdtimestamp);
			}
		}
	}

	/**
	 *  Set Value w/o check (update, r/o, ..).<br/>
	 * 	Used when Column is R/O.<br/>
	 *  Required for key and parent values.
	 *  @param ColumnName column name
	 *  @param value value to set
	 *  @return true if value set
	 */
	public final boolean set_ValueNoCheck (String ColumnName, Object value)
	{
		return set_Value(ColumnName, value, false);
	}   //  set_ValueNoCheck

	/**
	 *  Set Encrypted Value w/o check (update, r/o, ..).<br/>
	 * 	Used when Column is R/O.<br/>
	 *  Required for key and parent values.<br/>
	 *  @param ColumnName column name
	 *  @param value value to set
	 *  @return true if value set
	 */
	protected final boolean set_ValueNoCheckE (String ColumnName, Object value)
	{
		return set_ValueNoCheck (ColumnName, value);
	}	//	set_ValueNoCheckE

	/**
	 * Set value of Column
	 * @param columnName
	 * @param value
	 */
	public final void set_ValueOfColumn(String columnName, Object value)
	{
		set_ValueOfColumnReturningBoolean(columnName, value);
	}

	/**
	 * Set value of Column returning boolean
	 * @param columnName
	 * @param value
	 * @returns boolean indicating success or failure
	 */
	public final boolean set_ValueOfColumnReturningBoolean(String columnName, Object value)
	{
		int AD_Column_ID = p_info.getAD_Column_ID(columnName);
		if (AD_Column_ID > 0)
			return set_ValueOfColumnReturningBoolean(AD_Column_ID, value);
		else
			return false;
	}

	/**
	 *  Set Value of Column
	 *  @param AD_Column_ID column
	 *  @param value value to set
	 */
	public final void set_ValueOfColumn (int AD_Column_ID, Object value)
	{
		set_ValueOfColumnReturningBoolean (AD_Column_ID, value);
	}   //  setValueOfColumn


	/**
	 *  Set Value of Column
	 *  @param AD_Column_ID column
	 *  @param value value to set
	 *  @returns boolean indicating success or failure
	 */
	public final boolean set_ValueOfColumnReturningBoolean (int AD_Column_ID, Object value)
	{
		int index = p_info.getColumnIndex(AD_Column_ID);
		if (index < 0)
			throw new AdempiereUserError("Not found - AD_Column_ID=" + AD_Column_ID);
		String ColumnName = p_info.getColumnName(index);
		if (ColumnName.equals("IsApproved"))
			return set_ValueNoCheck(ColumnName, value);
		else
			return set_Value (index, value);
	}   //  setValueOfColumn


	/**
	 * 	Set Custom Column (column not in AD_Column).
	 *	@param columnName column
	 *	@param value value to set
	 */
	public final void set_CustomColumn (String columnName, Object value)
	{
		set_CustomColumnReturningBoolean (columnName, value);
	}	//	set_CustomColumn

	/**
	 * 	Set Custom Column (column not in AD_Column) returning boolean.
	 *	@param columnName column
	 *	@param value value to set
	 *  @returns boolean indicating success or failure
	 */
	public final boolean set_CustomColumnReturningBoolean (String columnName, Object value)
	{
		checkImmutable();
		
		// [ 1845793 ] PO.set_CustomColumn not updating correctly m_newValues
		// this is for columns not in PO - verify and call proper method if exists
		int poIndex = get_ColumnIndex(columnName);
		if (poIndex > 0) {
			// is not custom column - it exists in the PO
			return set_Value(columnName, value);
		}
		if (m_custom == null)
			m_custom = new HashMap<String,String>();
		String valueString = "NULL";
		if (value == null)
			;
		else if (value instanceof Number)
			valueString = value.toString();
		else if (value instanceof Boolean)
			valueString = ((Boolean)value).booleanValue() ? "'Y'" : "'N'";
		else if (value instanceof Timestamp)
			valueString = DB.TO_DATE((Timestamp)value, false);
		else //	if (value instanceof String)
			valueString = DB.TO_STRING(value.toString());
		//	Save it
		if (log.isLoggable(Level.INFO))log.log(Level.INFO, columnName + "=" + valueString);
		m_custom.put(columnName, valueString);
		return true;
	}	//	set_CustomColumn

	/**
	 *  Set (numeric) Key Value
	 *  @param ColumnName column name
	 *  @param value value to set
	 */
	private void set_Keys (String ColumnName, Object value)
	{
		checkImmutable();
		
		//	Update if KeyColumn
		for (int i = 0; i < m_IDs.length; i++)
		{
			if (ColumnName.equals (m_KeyColumns[i]))
			{
				m_IDs[i] = value;
			}
		}	//	for all key columns
	}	//	setKeys

	/**
	 *  Get Column Count
	 *  @return column count
	 */
	public int get_ColumnCount()
	{
		return p_info.getColumnCount();
	}   //  getColumnCount

	/**
	 *  Get Column Name
	 *  @param index column index
	 *  @return ColumnName
	 */
	public String get_ColumnName (int index)
	{
		return p_info.getColumnName (index);
	}   //  getColumnName

	/**
	 *  Get Column Label
	 *  @param index column index
	 *  @return Column Label
	 */
	protected String get_ColumnLabel (int index)
	{
		return p_info.getColumnLabel (index);
	}   //  getColumnLabel

	/**
	 *  Get Column Description
	 *  @param index column index
	 *  @return column description
	 */
	protected String get_ColumnDescription (int index)
	{
		return p_info.getColumnDescription (index);
	}   //  getColumnDescription

	/**
	 *  Is Column Mandatory
	 *  @param index column index
	 *  @return true if column is mandatory
	 */
	protected boolean isColumnMandatory (int index)
	{
		return p_info.isColumnMandatory(index);
	}   //  isColumnNandatory

	/**
	 *  Is Column Updateable
	 *  @param index column index
	 *  @return true if column is updateable
	 */
	protected boolean isColumnUpdateable (int index)
	{
		return p_info.isColumnUpdateable(index);
	}	//	isColumnUpdateable

	/**
	 *  Set Column Updateable
	 *  @param index column index
	 *  @param updateable column updateable
	 */
	protected void set_ColumnUpdateable (int index, boolean updateable)
	{
		p_info.setColumnUpdateable(index, updateable);
	}	//	setColumnUpdateable

	/**
	 * 	Set all columns updateable
	 * 	@param updateable updateable
	 */
	protected void setUpdateable (boolean updateable)
	{
		p_info.setUpdateable (updateable);
	}	//	setUpdateable

	/**
	 *  Get Column DisplayType
	 *  @param index column index
	 *  @return display type
	 */
	protected int get_ColumnDisplayType (int index)
	{
		return p_info.getColumnDisplayType(index);
	}	//	getColumnDisplayType

	/**
	 *  Get Lookup
	 *  @param index column index
	 *  @return Lookup or null
	 */
	protected Lookup get_ColumnLookup(int index)
	{
		return p_info.getColumnLookup(index);
	}   //  getColumnLookup

	/**
	 *  Get Column Index
	 *  @param columnName column name
	 *  @return index of column with ColumnName or -1 if not found
	 */
	public final int get_ColumnIndex (String columnName)
	{
		return p_info.getColumnIndex(columnName);
	}   //  getColumnIndex

	/**
	 * 	Get Display Text of column
	 *	@param columnName columnName
	 *	@param currentValue current value
	 *	@return display text or "./." for null
	 */
	public String get_DisplayValue(String columnName, boolean currentValue)
	{
		Object value = currentValue ? get_Value(columnName) : get_ValueOld(columnName);
		if (value == null)
			return "./.";
		String retValue = value.toString();
		int index = get_ColumnIndex(columnName);
		if (index < 0)
			return retValue;
		int dt = get_ColumnDisplayType(index);
		if (DisplayType.isText(dt) || DisplayType.YesNo == dt)
			return retValue;
		//	Lookup
		Lookup lookup = get_ColumnLookup(index);
		if (lookup != null)
			return lookup.getDisplay(value);
		//	Other
		return retValue;
	}	//	get_DisplayValue

	/**
	 * 	Copy old values of From to new values of To.<br/>
	 *  Does not copy Keys.
	 * 	@param from source PO
	 *  @param to target PO
	 * 	@param AD_Client_ID client
	 * 	@param AD_Org_ID org
	 */
	protected static void copyValues (PO from, PO to, int AD_Client_ID, int AD_Org_ID)
	{
		copyValues (from, to);
		to.setAD_Client_ID(AD_Client_ID);
		to.setAD_Org_ID(AD_Org_ID);
	}	//	copyValues

	/**
	 * 	Copy old values of From to new values of To.<br/>
	 *  Does not copy Keys and AD_Client_ID/AD_Org_ID.<br/>
	 * 	@param from source PO
	 *  @param to target PO
	 */
	public static void copyValues (PO from, PO to)
	{
		if (s_log.isLoggable(Level.FINE)) s_log.fine("From ID=" + from.get_ID() + " - To ID=" + to.get_ID());
		//	Different Classes
		if (from.getClass() != to.getClass())
		{
			for (int i1 = 0; i1 < from.m_oldValues.length; i1++)
			{
				String colName = from.p_info.getColumnName(i1);
				MColumn column = MColumn.get(from.getCtx(), from.p_info.getAD_Column_ID(colName));
				if (   column.isVirtualColumn()
					|| column.isKey()		//	KeyColumn
					|| column.isUUIDColumn() // IDEMPIERE-67
					|| column.isStandardColumn()
					|| ! column.isAllowCopy())
					continue;
				for (int i2 = 0; i2 < to.m_oldValues.length; i2++)
				{
					if (to.p_info.getColumnName(i2).equals(colName))
					{
						to.m_newValues[i2] = from.m_oldValues[i1];
						break;
					}
				}
			}	//	from loop
		}
		else	//	same class
		{
			for (int i = 0; i < from.m_oldValues.length; i++)
			{
				String colName = from.p_info.getColumnName(i);
				MColumn column = MColumn.get(from.getCtx(), from.p_info.getAD_Column_ID(colName));
				if (   column.isVirtualColumn()
					|| column.isKey()		//	KeyColumn
					|| column.isUUIDColumn()
					|| column.isStandardColumn()
					|| ! column.isAllowCopy())
					continue;
				to.m_newValues[i] = from.m_oldValues[i];
			}
		}	//	same class
	}	//	copy

	/**
	 *  Load record with ID
	 * 	@param ID ID
	 * 	@param trxName transaction name
	 *  @param virtualColumns names of virtual columns to load along with the regular table columns
	 */
	protected void load (int ID, String trxName, String ... virtualColumns)
	{
		checkImmutable();
		
		if (log.isLoggable(Level.FINEST)) log.finest("ID=" + ID);
		if (ID > 0)
		{
			setKeyInfo();
			m_IDs = new Object[] {Integer.valueOf(ID)};
			load(trxName, virtualColumns);
		}
		else	//	new
		{
			initNewRecord();
		}
	}	//	load

	/**
	 * Prepare PO for capturing of new record
	 */
	private void initNewRecord() {
		loadDefaults();
		m_createNew = true;
		setKeyInfo();	//	sets m_IDs
		loadComplete(true);
	}

	/**
	 * Load record with UUID
	 * 
	 * @param uuID universally unique identifier
	 * @param trxName transaction name
	 * @param virtualColumns names of virtual columns to load along with the regular table columns
	 */
	public void loadByUU(String uuID, String trxName, String ... virtualColumns)
	{
		if (Util.isEmpty(uuID, true))
		{
			throw new IllegalArgumentException("Invalid null or blank UU - Must pass valid UU");
		}
		
		// reset new values
		m_newValues = new Object[get_ColumnCount()];
		checkImmutable();

		if (log.isLoggable(Level.FINEST))
			log.finest("uuID=" + uuID);
			
		loadPO(uuID,trxName, virtualColumns);
	} // loadByUU

	/**
	 *  (re)Load record with m_ID[*]
	 *  @param trxName transaction
	 *  @param virtualColumns names of virtual columns to load along with the regular table columns
	 *  @return true if loaded
	 */
	public boolean load (String trxName, String ... virtualColumns) {
		return loadPO(null, trxName, virtualColumns);
	}
	
	/**
	 *  (re)Load record with uuID or {@link #m_IDs}
	 *  @param uuID RecrodUU if not null, load by uuID, otherwise by m_IDs
	 *  @param trxName transaction
	 *  @param virtualColumns names of virtual columns to load along with the regular table columns
	 *  @return true if loaded
	 */
	protected boolean loadPO (String uuID, String trxName, String ... virtualColumns)
	{
		if (log.isLoggable(Level.FINEST)) log.finest("UU=" + uuID);

		m_trxName = trxName;
		boolean success = true;
		StringBuilder sql = new StringBuilder("SELECT ");
		int size = get_ColumnCount();
		for (int i = 0; i < size; i++)
		{
			String columnSQL = p_info.getColumnSQL(i);
			if (p_info.isVirtualColumn(i))
			{
				boolean lazyLoad = true;
				if(virtualColumns != null)
				{
					for(String virtualColumn : virtualColumns)
					{
						if(p_info.getColumnName(i).equalsIgnoreCase(virtualColumn))
						{
							lazyLoad = false;
							break;
						}
					}
				}

				if(lazyLoad)
					continue;

			}
			else
			{
				columnSQL = DB.getDatabase().quoteColumnName(columnSQL);
			}
			if (i != 0)
				sql.append(",");
			sql.append(columnSQL);
		}
		sql.append(" FROM ").append(p_info.getTableName())
			.append(" WHERE ")
			.append(get_WhereClause(false,uuID));

		//
		if (log.isLoggable(Level.FINEST)) log.finest(get_WhereClause(true,uuID));
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try
		{
			pstmt = DB.prepareStatement(sql.toString(), m_trxName);	//	local trx only
			if (!Util.isEmpty(uuID, true))
			{
				pstmt.setString(1, uuID);
			}
			else
			{
				for (int i = 0; i < m_IDs.length; i++)
				{
					Object oo = m_IDs[i];
					if (oo instanceof Integer)
						pstmt.setInt(i+1, ((Integer)m_IDs[i]).intValue());
					else if (oo instanceof Boolean)
						pstmt.setString(i+1, ((Boolean) m_IDs[i] ? "Y" : "N"));
					else if (oo instanceof Timestamp)
						pstmt.setTimestamp(i+1, (Timestamp)m_IDs[i]);
					else
						pstmt.setString(i+1, m_IDs[i].toString());
				}
			}
			rs = pstmt.executeQuery();
			if (rs.next())
			{
				success = load(rs);
			}
			else
			{
				log.log(Level.SEVERE, "NO Data found for " + get_WhereClause(true,uuID), new Exception());
				m_IDs = new Object[] {I_ZERO};
				success = false;
			}
			m_createNew = false;
			//	reset new values
			m_newValues = new Object[size];
		}
		catch (Exception e)
		{
			String msg = "";
			if (m_trxName != null)
				msg = "[" + m_trxName + "] - ";
			msg += get_WhereClause(true,uuID)
				+ ", SQL=" + sql.toString();
			success = false;
			m_IDs = new Object[] {I_ZERO};
			log.log(Level.SEVERE, msg, e);
		}
		//	Finish
		finally {
			DB.close(rs, pstmt);
			rs = null; pstmt = null;
			if (is_Immutable())
				m_trxName = null;
		}
		loadComplete(success);
		return success;
	}   //  load

	/**
	 * 	Load from the current position of a ResultSet
	 * 	@param rs result set
	 * 	@return true if loaded
	 */
	protected boolean load (ResultSet rs)
	{
		int size = get_ColumnCount();
		boolean success = true;
		int index = 0;
		if (log.isLoggable(Level.FINEST)) log.finest("(rs)");
		loadedVirtualColumns.clear();
		//  load column values
		for (index = 0; index < size; index++)
		{
			if(!loadColumn(rs, index) && success)
				success = false;
		}
		m_createNew = false;
		setKeyInfo();
		loadComplete(success);
		return success;
	}	//	load

	/**
	 * Load column value coming from a {@link ResultSet}.
	 * @param rs {@link ResultSet} with its position set according to the model class instance.
	 * @param index Column index. Might not coincide with the index of the column within the {@link ResultSet}.
	 * @return true if loaded
	 * @see #m_oldValues
	 * @see POInfo#getColumnIndex(String)
	 */
	private boolean loadColumn(ResultSet rs, int index) {
		boolean success = true;
		String columnName = p_info.getColumnName(index);
		String[] selectColumns = MTable.getPartialPOResultSetColumns(rs);
		if (selectColumns != null && selectColumns.length > 0) {
			if (!p_info.isColumnAlwaysLoadedForPartialPO(index)) {
				Optional<String> optional = Arrays.stream(selectColumns).filter(e -> e.equalsIgnoreCase(columnName)).findFirst();
				if (!optional.isPresent()) {
					if (log.isLoggable(Level.FINER))log.log(Level.FINER, "Partial PO, Column not loaded: " + columnName);
					return true;
				}
			}
		}
		Class<?> clazz = p_info.getColumnClass(index);
		int dt = p_info.getColumnDisplayType(index);
		try
		{
			if (clazz == Integer.class)
				m_oldValues[index] = decrypt(index, Integer.valueOf(rs.getInt(columnName)));
			else if (clazz == BigDecimal.class)
				m_oldValues[index] = decrypt(index, rs.getBigDecimal(columnName));
			else if (clazz == Boolean.class)
				m_oldValues[index] = Boolean.valueOf("Y".equals(decrypt(index, rs.getString(columnName))));
			else if (clazz == Timestamp.class)
				m_oldValues[index] = decrypt(index, rs.getTimestamp(columnName));
			else if (DisplayType.isLOB(dt))
				m_oldValues[index] = get_LOB (rs.getObject(columnName));
			else if (clazz == String.class)
			{
				String value = (String)decrypt(index, rs.getString(columnName));
				if (value != null)
				{
					if (get_Table_ID() == I_AD_Column.Table_ID || get_Table_ID() == I_AD_Element.Table_ID
						|| get_Table_ID() == I_AD_Field.Table_ID)
					{
						if ("Description".equals(columnName) || "Help".equals(columnName))
						{
							value = value.intern();
						}
					}
				}
				m_oldValues[index] = value;
			}
			else
				m_oldValues[index] = loadSpecial(rs, index);
			//	NULL
			if (rs.wasNull() && m_oldValues[index] != null)
				m_oldValues[index] = null;

			// flag virtual column as loaded
			if(p_info.isVirtualColumn(index))
				loadedVirtualColumns.add(index);
			//
			if (CLogMgt.isLevelAll())
				log.finest(String.valueOf(index) + ": " + p_info.getColumnName(index)
					+ "(" + p_info.getColumnClass(index) + ") = " + m_oldValues[index]);
		}
		catch (SQLException e)
		{
			if (p_info.isVirtualColumn(index)) {
				if (log.isLoggable(Level.FINER))log.log(Level.FINER, "Virtual Column not loaded: " + columnName);
			} else {
				Level logLevel;
				if (DBException.isColumnNotFound(e))
					logLevel = Level.WARNING;
				else
					logLevel = Level.SEVERE;
				log.log(logLevel, "(rs) - " + String.valueOf(index)
					+ ": " + p_info.getTableName() + "." + p_info.getColumnName(index)
					+ " (" + p_info.getColumnClass(index) + ") - " + e);
				success = false;
			}
		}
		return success;
	}

	/**
	 * Load value for virtual column, only if it wasn't loaded previously.
	 * @param index Column index (see {@link POInfo#getColumnIndex(String)}).
	 */
	private void loadVirtualColumn(int index) {
		if(!m_createNew && !loadedVirtualColumns.contains(index)) {
			StringBuilder sql = new StringBuilder("SELECT ").append(p_info.getColumnSQL(index))
				.append(" FROM ").append(p_info.getTableName()).append(" WHERE ")
				.append(get_WhereClause(true, null));
			ResultSet rs = null;
			PreparedStatement pstmt = null;
			try
			{
				pstmt = DB.prepareStatement(sql.toString(), m_trxName);
				rs = pstmt.executeQuery();
				if (rs.next())
					loadColumn(rs, index);
				loadedVirtualColumns.add(index);
			}catch(Exception e){
				log.log(Level.SEVERE, "(rs) - " + String.valueOf(index)
				+ ": " + p_info.getTableName() + "." + p_info.getColumnName(index)
				+ " (" + p_info.getColumnClass(index) + ") - " + e);
			}finally {
				DB.close(rs, pstmt);
			}
		}
	}

	/**
	 * 	Get values from HashMap
	 * 	@param hmIn hash map
	 * 	@return true if loaded
	 */
	protected boolean load (HashMap<String,String> hmIn)
	{
		checkImmutable();
		
		int size = get_ColumnCount();
		boolean success = true;
		int index = 0;
		log.finest("(hm)");
		//  load column values
		for (index = 0; index < size; index++)
		{
			String columnName = p_info.getColumnName(index);
			String value = (String)hmIn.get(columnName);
			if (value == null)
				continue;
			Class<?> clazz = p_info.getColumnClass(index);
			int dt = p_info.getColumnDisplayType(index);
			try
			{
				if (clazz == Integer.class)
					m_oldValues[index] = Integer.valueOf(value);
				else if (clazz == BigDecimal.class)
					m_oldValues[index] = new BigDecimal(value);
				else if (clazz == Boolean.class)
					m_oldValues[index] = Boolean.valueOf("Y".equals(value));
				else if (clazz == Timestamp.class)
					m_oldValues[index] = Timestamp.valueOf(value);
				else if (DisplayType.isLOB(dt))
					m_oldValues[index] = null;	//	get_LOB (rs.getObject(columnName));
				else if (clazz == String.class)
					m_oldValues[index] = value;
				else
					m_oldValues[index] = null;	// loadSpecial(rs, index);
				//
				if (CLogMgt.isLevelAll())
					log.finest(String.valueOf(index) + ": " + p_info.getColumnName(index)
						+ "(" + p_info.getColumnClass(index) + ") = " + m_oldValues[index]);
			}
			catch (Exception e)
			{
				if (p_info.isVirtualColumn(index)) {
					if (log.isLoggable(Level.FINER))log.log(Level.FINER, "Virtual Column not loaded: " + columnName);
				} else {
					log.log(Level.SEVERE, "(ht) - " + String.valueOf(index)
						+ ": " + p_info.getTableName() + "." + p_info.getColumnName(index)
						+ " (" + p_info.getColumnClass(index) + ") - " + e);
					success = false;
				}
			}
		}
		m_createNew = false;
		//	Overwrite
		setStandardDefaults();
		setKeyInfo();
		loadComplete(success);
		return success;
	}	//	load

	/**
	 * Throw exception if PO is immutable.
	 */
	protected void checkImmutable() {
		if (is_Immutable())
		{
			throw new IllegalStateException("PO is Immutable: " + getClass().getName());
		}
	}

	/**
	 *  Create hash map with column name as value and column value as value (converted to string)
	 *  @return HashMap
	 */
	protected HashMap<String,String> get_HashMap()
	{
		HashMap<String,String> hmOut = new HashMap<String,String>();
		int size = get_ColumnCount();
		for (int i = 0; i < size; i++)
		{
			Object value = get_Value(i);
			//	Don't insert NULL values (allows Database defaults)
			if (value == null
				|| p_info.isVirtualColumn(i))
				continue;
			//	Display Type
			int dt = p_info.getColumnDisplayType(i);
			//  Based on class of definition, not class of value
			Class<?> c = p_info.getColumnClass(i);
			String stringValue = null;
			if (c == Object.class)
				;
			else if (value == null || value.equals (Null.NULL))
				;
			else if (value instanceof Integer || value instanceof BigDecimal)
				stringValue = value.toString();
			else if (c == Boolean.class)
			{
				boolean bValue = false;
				if (value instanceof Boolean)
					bValue = ((Boolean)value).booleanValue();
				else
					bValue = "Y".equals(value);
				stringValue = bValue ? "Y" : "N";
			}
			else if (value instanceof Timestamp)
				stringValue = value.toString();
			else if (c == String.class)
				stringValue = (String)value;
			else if (DisplayType.isLOB(dt))
				;
			else
				;
			//
			if (stringValue != null)
				hmOut.put(p_info.getColumnName(i), stringValue);
		}
		//	Custom Columns
		if (m_custom != null)
		{
			Iterator<String> it = m_custom.keySet().iterator();
			while (it.hasNext())
			{
				String column = (String)it.next();
				String value = (String)m_custom.get(column);
				if (value != null)
					hmOut.put(column, value);
			}
			m_custom = null;
		}
		return hmOut;
	}   //  get_HashMap

	/**
	 *  Load data for custom Java type that has no build in implementation (images, ..).
	 *  To be implemented in sub-classes (default implementation is nop and just return null).
	 *  @param rs result set
	 *  @param index column index
	 *  @return value loaded value
	 *  @throws SQLException
	 */
	protected Object loadSpecial (ResultSet rs, int index) throws SQLException
	{
		if (log.isLoggable(Level.FINEST)) log.finest("(NOP) - " + p_info.getColumnName(index));
		return null;
	}   //  loadSpecial

	/**
	 *  Call when load of PO is complete.<br/>
	 *  Default implementation is nop, to be implemented in sub-classes that needed it.
	 * 	@param success success
	 */
	protected void loadComplete (boolean success)
	{
	}   //  loadComplete

	/**
	 *	Load default value of columns.
	 */
	protected void loadDefaults()
	{
		setStandardDefaults();
	}	//	loadDefaults

	/**
	 *  Set standard default values.<br/>
	 *  Client, Org, Created/Updated, *By, IsActive, Processed, Processing and Posted.
	 */
	protected void setStandardDefaults()
	{
		int size = get_ColumnCount();
		for (int i = 0; i < size; i++)
		{
			if (p_info.isVirtualColumn(i))
				continue;
			String colName = p_info.getColumnName(i);
			//  Set Standard Values
			if (colName.endsWith("tedBy"))
				m_newValues[i] = Integer.valueOf(Env.getContextAsInt(p_ctx, Env.AD_USER_ID));
			else if (colName.equals("Created") || colName.equals("Updated"))
				m_newValues[i] = new Timestamp (System.currentTimeMillis());
			else if (colName.equals(p_info.getTableName() + "_ID"))    //  KeyColumn
				m_newValues[i] = I_ZERO;
			else if (colName.equals("IsActive"))
				m_newValues[i] = Boolean.TRUE;
			else if (colName.equals("AD_Client_ID"))
				m_newValues[i] = Integer.valueOf(Env.getAD_Client_ID(p_ctx));
			else if (colName.equals("AD_Org_ID"))
				m_newValues[i] = Integer.valueOf(Env.getAD_Org_ID(p_ctx));
			else if (colName.equals("Processed"))
				m_newValues[i] = Boolean.FALSE;
			else if (colName.equals("Processing"))
				m_newValues[i] = Boolean.FALSE;
			else if (colName.equals("Posted"))
				m_newValues[i] = Boolean.FALSE;
		}
	}   //  setDefaults

	/**
	 * Load Key Info (IDs and KeyColumns).
	 */
	private void setKeyInfo()
	{
		m_KeyColumns = null;
		//	Search for Primary Key
		for (int i = 0; i < p_info.getColumnCount(); i++)
		{
			if (p_info.isKey(i))
			{
				String ColumnName = p_info.getColumnName(i);
				m_KeyColumns = new String[] {ColumnName};
				if (p_info.getColumnName(i).endsWith("_ID"))
				{
					Integer ii = (Integer)get_Value(i);
					if (ii == null)
						m_IDs = new Object[] {I_ZERO};
					else
						m_IDs = new Object[] {ii};
					if (log.isLoggable(Level.FINEST)) log.finest("(PK) " + ColumnName + "=" + ii);
				}
				else
				{
					Object oo = get_Value(i);
					if (oo == null)
						m_IDs = new Object[] {null};
					else
						m_IDs = new Object[] {oo};
					if (log.isLoggable(Level.FINEST)) log.finest("(PK) " + ColumnName + "=" + oo);
				}
				return;
			}
		}	//	primary key search

		//	Search for Parents
		ArrayList<String> columnNames = new ArrayList<String>();
		for (int i = 0; i < p_info.getColumnCount(); i++)
		{
			if (p_info.isColumnParent(i))
				columnNames.add(p_info.getColumnName(i));
		}
		//	Set FKs
		int size = columnNames.size();
		if (size > 0)
		{
			m_IDs = new Object[size];
			m_KeyColumns = new String[size];
			for (int i = 0; i < size; i++)
			{
				m_KeyColumns[i] = (String)columnNames.get(i);
				if (m_KeyColumns[i].endsWith("_ID"))
				{
					Integer ii = null;
					try
					{
						ii = (Integer)get_Value(m_KeyColumns[i]);
					}
					catch (Exception e)
					{
						log.log(Level.SEVERE, "", e);
					}
					if (ii != null)
						m_IDs[i] = ii;
				}
				else
					m_IDs[i] = get_Value(m_KeyColumns[i]);
				if (log.isLoggable(Level.FINEST)) log.finest("(FK) " + m_KeyColumns[i] + "=" + m_IDs[i]);
			}
		}

		if (m_KeyColumns == null || m_KeyColumns.length == 0)
		{
			//	Search for UUID Key
			for (int i = 0; i < p_info.getColumnCount(); i++)
			{
				String ColumnName = p_info.getColumnName(i);
				if (ColumnName.equals(PO.getUUIDColumnName(get_TableName())))
				{
					m_KeyColumns = new String[] {ColumnName};
					Object oo = get_Value(i);
					if (oo == null)
						m_IDs = new Object[] {null};
					else
						m_IDs = new Object[] {oo};
					if (log.isLoggable(Level.FINEST)) log.finest("(UU) " + ColumnName + "=" + oo);
					return;
				}
			}	//	UUID key search
		}

		if (m_KeyColumns == null || m_KeyColumns.length == 0)
			throw new IllegalStateException("No PK, UU nor FK - " + p_info.getTableName());
	}	//	setKeyInfo

	/**
	 *  Is all mandatory Fields filled (i.e. can we save)?.<br/>
	 *  Stops at first null mandatory field.
	 *  @return true if all mandatory fields are ok
	 */
	protected boolean isMandatoryOK()
	{
		int size = get_ColumnCount();
		for (int i = 0; i < size; i++)
		{
			if (p_info.isColumnMandatory(i))
			{
				if (p_info.isVirtualColumn(i))
					continue;
				if (get_Value(i) == null || get_Value(i).equals(Null.NULL))
				{
					if (log.isLoggable(Level.INFO)) log.info(p_info.getColumnName(i));
					return false;
				}
			}
		}
		return true;
	}   //  isMandatoryOK

	/**
	 * 	Set AD_Client
	 * 	@param AD_Client_ID client
	 */
	final protected void setAD_Client_ID (int AD_Client_ID)
	{
		set_ValueNoCheck ("AD_Client_ID", Integer.valueOf(AD_Client_ID));
	}	//	setAD_Client_ID

	/**
	 * 	Get AD_Client
	 * 	@return AD_Client_ID
	 */
	public final int getAD_Client_ID()
	{
		Integer ii = (Integer)get_Value("AD_Client_ID");
		if (ii == null)
			return 0;
		return ii.intValue();
	}	//	getAD_Client_ID

	/**
	 * 	Set AD_Org
	 * 	@param AD_Org_ID org
	 */
	final public void setAD_Org_ID (int AD_Org_ID)
	{
		set_ValueNoCheck ("AD_Org_ID", Integer.valueOf(AD_Org_ID));
	}	//	setAD_Org_ID

	/**
	 * 	Get AD_Org
	 * 	@return AD_Org_ID
	 */
	public int getAD_Org_ID()
	{
		Integer ii = (Integer)get_Value("AD_Org_ID");
		if (ii == null)
			return 0;
		return ii.intValue();
	}	//	getAD_Org_ID

	/**
	 * 	Overwrite Client Org if different
	 *	@param AD_Client_ID client
	 *	@param AD_Org_ID org
	 */
	protected void setClientOrg (int AD_Client_ID, int AD_Org_ID)
	{
		if (AD_Client_ID != getAD_Client_ID())
			setAD_Client_ID(AD_Client_ID);
		if (AD_Org_ID != getAD_Org_ID())
			setAD_Org_ID(AD_Org_ID);
	}	//	setClientOrg

	/**
	 * 	Overwrite Client Org if different
	 *	@param po source persistent object
	 */
	protected void setClientOrg (PO po)
	{
		setClientOrg(po.getAD_Client_ID(), po.getAD_Org_ID());
	}	//	setClientOrg

	/**
	 * 	Set Active
	 * 	@param active
	 */
	public final void setIsActive (boolean active)
	{
		set_Value("IsActive", Boolean.valueOf(active));
	}	//	setActive

	/**
	 *	Is Active
	 *  @return is active
	 */
	public final boolean isActive()
	{
		Boolean bb = (Boolean)get_Value("IsActive");
		if (bb != null)
			return bb.booleanValue();
		return false;
	}	//	isActive

	/**
	 * 	Get Created
	 * 	@return created
	 */
	final public Timestamp getCreated()
	{
		return (Timestamp)get_Value("Created");
	}	//	getCreated

	/**
	 * 	Get Updated
	 *	@return updated
	 */
	final public Timestamp getUpdated()
	{
		return (Timestamp)get_Value("Updated");
	}	//	getUpdated

	/**
	 * 	Get CreatedBy
	 * 	@return AD_User_ID
	 */
	final public int getCreatedBy()
	{
		Integer ii = (Integer)get_Value("CreatedBy");
		if (ii == null)
			return 0;
		return ii.intValue();
	}	//	getCreateddBy

	/**
	 * 	Get UpdatedBy
	 * 	@return AD_User_ID
	 */
	final public int getUpdatedBy()
	{
		Integer ii = (Integer)get_Value("UpdatedBy");
		if (ii == null)
			return 0;
		return ii.intValue();
	}	//	getUpdatedBy

	/**
	 * 	Set UpdatedBy
	 * 	@param AD_User_ID user
	 */
	final protected void setUpdatedBy (int AD_User_ID)
	{
		set_ValueNoCheck ("UpdatedBy", Integer.valueOf(AD_User_ID));
	}	//	setAD_User_ID

	private static final String TRANSLATION_CACHE_TABLE_NAME = "PO_Trl";
	
	/**	Cache						*/
	private static CCache<String,String> trl_cache	= new CCache<String,String>(TRANSLATION_CACHE_TABLE_NAME, 5, CCache.DEFAULT_EXPIRE_MINUTE, false);
	/** Cache for foreign keys */
	private static CCache<Integer,List<ValueNamePair>> fks_cache	= new CCache<Integer,List<ValueNamePair>>("FKs", 5);

	/**
	 * Get translated value for column
	 * @param columnName
	 * @param AD_Language
	 * @return translated value
	 */
	public String get_Translation (String columnName, String AD_Language)
	{
		return get_Translation(columnName, AD_Language, false, true);
	}

	/**
	 * Get Translation of column (if needed).<br/>
	 * It checks if the base language is used or the column is not translated.<br/>
	 * If there is no translation then it fallback to original value.
	 * @param columnName
	 * @param AD_Language
	 * @param reload don't use cache, reload from DB
	 * @param fallback fallback to base if no translation found
	 * @return translated string
	 * @throws IllegalArgumentException if columnName or AD_Language is null or model has multiple PK
	 */
	public String get_Translation (String columnName, String AD_Language, boolean reload, boolean fallback)
	{
		//
		// Check if columnName, AD_Language is valid or table support translation (has 1 PK) => error
		if (   columnName == null 
			|| AD_Language == null
			|| m_IDs.length > 1
			|| (m_IDs[0] instanceof Integer && m_IDs[0].equals(I_ZERO) && ! MTable.isZeroIDTable(get_TableName()))
			|| (m_IDs[0] instanceof String && Util.isEmpty((String)m_IDs[0]))
			|| !(m_IDs[0] instanceof Integer || m_IDs[0] instanceof String))
		{
			throw new IllegalArgumentException("ColumnName=" + columnName
												+ ", AD_Language=" + AD_Language
												+ ", ID.length=" + m_IDs.length
												+ ", ID=" + m_IDs[0]);
		}

		String key = getTrlCacheKey(columnName, AD_Language);
		String retValue = null;
		if (! reload && trl_cache.containsKey(key)) {
			retValue = trl_cache.get(key);
			return retValue;

		} else {
			//
			// Check if NOT base language and column is translated => load trl from db
			if (!Env.isBaseLanguage(AD_Language, get_TableName())
					&& p_info.isColumnTranslated(p_info.getColumnIndex(columnName))
				)
			{
				// Load translation from database
				int ID = ((Integer)m_IDs[0]).intValue();
				StringBuilder sql = new StringBuilder("SELECT ").append(columnName)
										.append(" FROM ").append(p_info.getTableName()).append("_Trl WHERE ")
										.append(m_KeyColumns[0]).append("=?")
										.append(" AND AD_Language=?");
				retValue = DB.getSQLValueString(get_TrxName(), sql.toString(), ID, AD_Language);
			}
		}
		//
		// If no translation found or not translated, fallback to original:
		if (retValue == null && fallback) {
			Object val = get_Value(columnName);
			retValue = (val != null ? val.toString() : null);
		}
		trl_cache.put(key, retValue);
		//
		return retValue;
	}	//	get_Translation

	/** 
	 * Get the key used in the translation cache
	 * @return key used in the translation cache
	 */
	private String getTrlCacheKey(String columnName, String AD_Language) {
		return toTrlCacheKey(get_TableName(), columnName, get_ID(), AD_Language);
	}

	private static String toTrlCacheKey(String tableName, String columnName, int id, String AD_Language) {
		return tableName + "." + columnName + "|" + id + "|" + AD_Language;
	}
	
	/**
	 * Get Translation of column
	 * @param columnName
	 * @return translated text
	 */
	public String get_Translation (String columnName)
	{
		return get_Translation(columnName, true);
	}
	
	/**
	 * Get Translation of column
	 * @param columnName
	 * @param AD_Language
	 * @param reload don't use cache, reload from DB
	 * @return translated text
	 */
	public String get_Translation (String columnName, String AD_Language, boolean reload)
	{
		return get_Translation(columnName, AD_Language, reload, true);
	}
	
	/**
	 * Get Translation of column
	 * @param columnName
	 * @param fallback fallback to base if no translation found
	 * @return translation
	 */
	public String get_Translation (String columnName, boolean fallback)
	{
		return get_Translation(columnName, Env.getAD_Language(getCtx()), false, fallback);
	}

	/**
	 * 	Is new record
	 *	@return true if new
	 */
	public boolean is_new()
	{
		if (m_createNew)
			return true;
		//
		for (int i = 0; i < m_IDs.length; i++)
		{
			if (m_IDs[i].equals(I_ZERO) || m_IDs[i] == Null.NULL)
				continue;
			return false;	//	one value is non-zero
		}
		if (MTable.isZeroIDTable(get_TableName()))
			return false;
		return true;
	}	//	is_new

	/**
	 * Classes which override save() method:
	 * org.compiere.process.DocActionTemplate
	 * org.compiere.model.MClient
	 * org.compiere.model.MClientInfo
	 * org.compiere.model.MSystem
	 */
	/**
	 *  Update or insert new record.<br/>
	 * 	To reload call load().
	 *  @return true if saved
	 */
	public boolean save()
	{
		CLogger.resetLast();
		boolean newRecord = is_new();	//	save locally as load resets
		if (!newRecord && !is_Changed())
		{
			if (log.isLoggable(Level.FINE)) log.fine("Nothing changed - " + p_info.getTableName());
			return true;
		}

		if (!checkReadOnlySession())
			return false;
		checkImmutable();
		checkValidContext();
		checkCrossTenant(true);
		checkRecordIDCrossTenant();
		checkRecordUUCrossTenant();

		if (m_setErrorsFilled) {
			for (int i = 0; i < m_setErrors.length; i++) {
				ValueNamePair setError = m_setErrors[i];
				if (setError != null) {
					log.saveError(setError.getValue(), Msg.getElement(getCtx(), p_info.getColumnName(i)) + " - " + setError.getName());
					return false;
				}
			}
		}

		Trx localTrx = null;
		Trx trx = null;
		Savepoint savepoint = null;
		if (m_trxName == null)
		{
			StringBuilder l_trxname = new StringBuilder(LOCAL_TRX_PREFIX)
				.append(get_TableName());
			if (l_trxname.length() > 23)
				l_trxname.setLength(23);
			m_trxName = Trx.createTrxName(l_trxname.toString());
			localTrx = Trx.get(m_trxName, true);
			if (newRecord)
				localTrx.setDisplayName(getClass().getName() + "_insert");
			else
				localTrx.setDisplayName(getClass().getName() + "_update_ID" + get_ID());
			localTrx.getConnection();
		}
		else
		{
			trx = Trx.get(m_trxName, false);
			if (trx == null)
			{
				// Using a trx that was previously closed or never opened
				// Creating and starting the transaction right here, but please note
				// that this is not a good practice
				trx = Trx.get(m_trxName, true);
				log.severe("Transaction closed or never opened ("+m_trxName+") => starting now --> " + toString());
			}
		}

		//	Before Save
		try
		{
			// If not a localTrx we need to set a savepoint for rollback
			if (localTrx == null)
				savepoint = trx.setSavepoint(null);

			if (!beforeSave(newRecord))
			{
				log.warning("beforeSave failed - " + toString());
				if (localTrx != null)
				{
					localTrx.rollback();
					localTrx.close();
					m_trxName = null;
				}
				else
				{
					trx.rollback(savepoint);
					savepoint = null;
				}
				return false;
			}
		}
		catch (Exception e)
		{
			log.log(Level.WARNING, "beforeSave - " + toString(), e);
			String msg = DBException.getDefaultDBExceptionMessage(e);
			log.saveError(msg != null ? msg : "Error", e, false);
			if (localTrx != null)
			{
				localTrx.rollback();
				localTrx.close();
				m_trxName = null;
			}
			else if (savepoint != null)
			{
				try
				{
					trx.rollback(savepoint);
				} catch (SQLException e1){}
				savepoint = null;
			}
			return false;
		}

		try
		{
			// Call ModelValidators TYPE_NEW/TYPE_CHANGE
			String errorMsg = ModelValidationEngine.get().fireModelChange
				(this, newRecord ? ModelValidator.TYPE_NEW : ModelValidator.TYPE_CHANGE);
			if (errorMsg != null)
			{
				log.warning("Validation failed - " + errorMsg);
				log.saveError("Error", errorMsg);
				if (localTrx != null)
				{
					localTrx.rollback();
					m_trxName = null;
				}
				else
				{
					trx.rollback(savepoint);
				}
				return false;
			}

		//	Organization Check
		if (getAD_Org_ID() == 0
			&& (get_AccessLevel() == ACCESSLEVEL_ORG
				|| (get_AccessLevel() == ACCESSLEVEL_CLIENTORG
					&& MClientShare.isOrgLevelOnly(getAD_Client_ID(), get_Table_ID()))))
		{
			log.saveError("FillMandatory", Msg.getElement(getCtx(), "AD_Org_ID"));
			return false;
		}
		//	Should be Org 0
		if (getAD_Org_ID() != 0)
		{
			boolean reset = get_AccessLevel() == ACCESSLEVEL_SYSTEM;
			if (!reset && MClientShare.isClientLevelOnly(getAD_Client_ID(), get_Table_ID()))
			{
				reset = get_AccessLevel() == ACCESSLEVEL_CLIENT
					|| get_AccessLevel() == ACCESSLEVEL_SYSTEMCLIENT
					|| get_AccessLevel() == ACCESSLEVEL_ALL
					|| get_AccessLevel() == ACCESSLEVEL_CLIENTORG;
			}
			if (reset)
			{
				log.warning("Set Org to 0");
				setAD_Org_ID(0);
			}
		}

			//	Save
			if (newRecord)
			{
				boolean b = saveNew();
				if (b)
				{
					if (localTrx != null)
						return localTrx.commit();
					else
						return b;
				}
				else
				{
					validateUniqueIndex();
					if (localTrx != null)
						localTrx.rollback();
					else
						trx.rollback(savepoint);
					return b;
				}
			}
			else
			{
				boolean b = saveUpdate();
				if (b)
				{
					if (localTrx != null)
						return localTrx.commit();
					else
						return b;
				}
				else
				{
					validateUniqueIndex();
					if (localTrx != null)
						localTrx.rollback();
					else
						trx.rollback(savepoint);
					return b;
				}
			}
		}
		catch (Exception e)
		{
			log.log(Level.WARNING, "afterSave - " + toString(), e);
			String msg = DBException.getDefaultDBExceptionMessage(e);
			log.saveError(msg != null ? msg : "Error", e);
			if (localTrx != null)
			{
				localTrx.rollback();
			}
			else if (savepoint != null)
			{
				try
				{
					trx.rollback(savepoint);
				} catch (SQLException e1){}
				savepoint = null;
			}
			return false;
		}
		finally
		{
			if (localTrx != null)
			{
				localTrx.close();
				m_trxName = null;
			}
			else
			{
				if (savepoint != null)
				{
					try {
						trx.releaseSavepoint(savepoint);
					} catch (SQLException e) {
						e.printStackTrace();
					}
				}
				savepoint = null;
				trx = null;
			}
		}
	}	//	save


	/**
	 * Tables allowed to be written in a read-only session
	 */
	final Set<String> ALLOWED_TABLES_IN_RO_SESSION = new HashSet<>(Arrays.asList(new String[] {
			"AD_ChangeLog",
			"AD_Preference",
			"AD_Session",
			"AD_UserPreference",
			"AD_Wlistbox_Customization"
	}));

	/**
	 * Do not allow saving if in a read-only session, except the allowed tables
	 * @return
	 */
	private boolean checkReadOnlySession() {
		if (Env.isReadOnlySession()) {
			if (! ALLOWED_TABLES_IN_RO_SESSION.contains(get_TableName())) {
				log.saveError("Error", Msg.getMsg(getCtx(), "ReadOnlySession") + " [" + get_TableName() + "]");
				return false;
			}
		}
		return true;
	}

	/**
	 * Update or insert new record.
	 * @throws AdempiereException if save fail
	 * @see #save()
	 */
	public void saveEx() throws AdempiereException
	{
		if (!save()) {
			StringBuilder msg = new StringBuilder();
			ValueNamePair err = CLogger.retrieveError();
			String val = err != null ? Msg.translate(getCtx(), err.getValue()) : "";
			if (err != null) {
				if (val != null) {
					msg.append(val);
					if (val.endsWith(":"))
						msg.append(" ");
					else if (! val.endsWith(": "))
						msg.append(": ");
				}
				msg.append(err.getName());
			}
			if (msg.length() == 0)
				msg.append("SaveError");
			Exception ex = CLogger.retrieveException();
			throw new AdempiereException(msg.toString(), ex);
		}
	}

	/**
	 * Update or insert new record, used when writing a cross tenant record.
	 * @throws AdempiereException
	 * @see #save()
	 */
	public boolean saveCrossTenantSafe() {
		boolean crossTenantSet = isSafeCrossTenant.get();
		try {
			if (!crossTenantSet)
				PO.setCrossTenantSafe();
			return save();
		} finally {
			if (!crossTenantSet)
				PO.clearCrossTenantSafe();
		}
	}
	
	/**
	 * Update or insert new record, used when writing a cross tenant record.
	 * @throws AdempiereException if save fail
	 * @see #saveEx()
	 */
	public void saveCrossTenantSafeEx() {
		boolean crossTenantSet = isSafeCrossTenant.get();
		try {
			if (!crossTenantSet)
				PO.setCrossTenantSafe();
			saveEx();
		} finally {
			if (!crossTenantSet)
				PO.clearCrossTenantSafe();
		}
	}

	/**
	 * 	Finish saving of PO to DB.
	 *	@param newRecord true for new record
	 *	@param success current save state
	 *	@return true if saved
	 */
	private boolean saveFinish (boolean newRecord, boolean success)
	{
		//	Translations
		if (success)
		{
			if (newRecord)
				insertTranslations();
			else
				updateTranslations();

			// table with potential tree
			if (get_ColumnIndex("IsSummary") >= 0) {
				if (newRecord && getTable().hasCustomTree())
					insert_Tree(MTree_Base.TREETYPE_CustomTable);
				int idxValue = get_ColumnIndex("Value");
				if (getTable().hasCustomTree() && (newRecord || (idxValue >= 0 && is_ValueChanged(idxValue))))
					update_Tree(MTree_Base.TREETYPE_CustomTable);
			}
		}
		//
		try
		{
			success = afterSave (newRecord, success);
		}
		catch (Exception e)
		{
			log.log(Level.WARNING, "afterSave", e);
			log.saveError("Error", e, false);
			success = false;
		}
		// Call ModelValidators TYPE_AFTER_NEW/TYPE_AFTER_CHANGE - teo_sarca [ 1675490 ]
		if (success) {
			String errorMsg = ModelValidationEngine.get().fireModelChange
				(this, newRecord ?
							(isReplication() ? ModelValidator.TYPE_AFTER_NEW_REPLICATION : ModelValidator.TYPE_AFTER_NEW)
						:
							(isReplication() ? ModelValidator.TYPE_AFTER_CHANGE_REPLICATION : ModelValidator.TYPE_AFTER_CHANGE)
				);
			setReplication(false);
			if (errorMsg != null) {
				log.saveError("Error", errorMsg);
				success = false;
			}
		}
		
		//collect changed columns for translation cache reset below
		List<String> updatedColumns = new ArrayList<>();
		//	OK
		if (success)
		{
			//post osgi event
			String topic = newRecord ? IEventTopics.PO_POST_CREATE : IEventTopics.PO_POST_UPADTE;
			Event event = EventManager.newEvent(topic, this, true);
			EventManager.getInstance().postEvent(event);

			if (s_docWFMgr == null)
			{
				try
				{
					Class.forName("org.compiere.wf.DocWorkflowManager");
				}
				catch (Exception e)
				{
				}
			}
			if (s_docWFMgr != null)
				s_docWFMgr.process (this, p_info.getAD_Table_ID());

			//	Copy to Old values
			int size = p_info.getColumnCount();
			for (int i = 0; i < size; i++)
			{
				if (is_ValueChanged(i))
					updatedColumns.add(p_info.getColumnName(i));
				if (m_newValues[i] != null)
				{
					if (m_newValues[i] == Null.NULL)
						m_oldValues[i] = null;
					else
						m_oldValues[i] = m_newValues[i];
				}
			}
			m_newValues = new Object[size];
			m_createNew = false;
		}
		if (!newRecord && success)
			MRecentItem.clearLabel(p_info.getAD_Table_ID(), get_ID(), get_UUID());
		if (success && CacheMgt.get().hasCache(p_info.getTableName())) {
			boolean cacheResetScheduled = false;
			if (get_TrxName() != null) {
				Trx trx = Trx.get(get_TrxName(), false);
				if (trx != null) {
					trx.addTrxEventListener(new TrxEventListener() {
						@Override
						public void afterRollback(Trx trx, boolean success) {
							trx.removeTrxEventListener(this);
						}
						@Override
						public void afterCommit(Trx sav, boolean success) {
							if (success)
								if (!newRecord)
									Adempiere.getThreadPoolExecutor().submit(() -> CacheMgt.get().reset(p_info.getTableName(), get_ID()));
								else if (get_ID() > 0)
									Adempiere.getThreadPoolExecutor().submit(() -> CacheMgt.get().newRecord(p_info.getTableName(), get_ID()));
							trx.removeTrxEventListener(this);
						}
						@Override
						public void afterClose(Trx trx) {
						}
					});
					cacheResetScheduled = true;
				}
			}
			if (!cacheResetScheduled) {
				if (!newRecord)
					Adempiere.getThreadPoolExecutor().submit(() -> CacheMgt.get().reset(p_info.getTableName(), get_ID()));
				else if (get_ID() > 0)
					Adempiere.getThreadPoolExecutor().submit(() -> CacheMgt.get().newRecord(p_info.getTableName(), get_ID()));
			}
		} else if (success && p_info.getTableName().endsWith("_Trl") && CacheMgt.get().hasCache(TRANSLATION_CACHE_TABLE_NAME) && !newRecord) {
			MTable table = MTable.get(getCtx(), p_info.getTableName().substring(0, p_info.getTableName().length() - 4));
			POInfo parentInfo = POInfo.getPOInfo(getCtx(), table.getAD_Table_ID());
			List<String> translatedColumns = new ArrayList<>();
			for (int i = 0; i < parentInfo.getColumnCount(); i++)
			{
				String columnName = parentInfo.getColumnName(i);
				if (parentInfo.isColumnTranslated(i) && updatedColumns.contains(columnName))
				{
					translatedColumns.add(columnName);
				}
			}
			if (translatedColumns.size() > 0) {
				int id = get_ValueAsInt(table.getKeyColumns()[0]);
				boolean cacheResetScheduled = false;
				if (get_TrxName() != null) {
					Trx trx = Trx.get(get_TrxName(), false);
					if (trx != null) {
						trx.addTrxEventListener(new TrxEventListener() {
							@Override
							public void afterRollback(Trx trx, boolean success) {
								trx.removeTrxEventListener(this);
							}
							@Override
							public void afterCommit(Trx sav, boolean success) {
								if (success)
									Adempiere.getThreadPoolExecutor().submit(() -> { 
										for (String column : translatedColumns) {
											CacheMgt.get().reset(TRANSLATION_CACHE_TABLE_NAME, 
												toTrlCacheKey(table.getTableName(), column, id, get_ValueAsString("AD_Language")));
										}
									});
								trx.removeTrxEventListener(this);
							}
							@Override
							public void afterClose(Trx trx) {
							}
						});
						cacheResetScheduled = true;
					}
				}
				if (!cacheResetScheduled) {
					Adempiere.getThreadPoolExecutor().submit(() -> {
						for (String column : translatedColumns) {
							CacheMgt.get().reset(TRANSLATION_CACHE_TABLE_NAME, 
								toTrlCacheKey(table.getTableName(), column, id, get_ValueAsString("AD_Language")));
						}
					});
				}
			}
		}
		
		return success;
	}	//	saveFinish

	/**
	 * Get the MTable object associated to this PO
	 * @return MTable
	 */
	private MTable getTable() {
		return MTable.get(getCtx(), get_TableName());
	}

	/**
	 *  Update or insert new record.<br/>
	 * 	To reload call load().
	 *	@param trxName transaction
	 *  @return true if saved
	 */
	public boolean save (String trxName)
	{
		set_TrxName(trxName);
		return save();
	}	//	save

	/**
	 * Save for replication.
	 * @param isFromReplication
	 * @throws AdempiereException
	 */
	public void saveReplica (boolean isFromReplication) throws AdempiereException
	{
		checkImmutable();
		setReplication(isFromReplication);
		saveEx();
	}

	/**
	 * Update or insert new record, used when writing a cross tenant record.
	 * @param trxName transaction
	 * @throws AdempiereException if save fail
	 * @see #saveEx(String)
	 */
	public void saveCrossTenantSafeEx(String trxName) {
		boolean crossTenantSet = isSafeCrossTenant.get();
		try {
			if (!crossTenantSet)
				PO.setCrossTenantSafe();
			saveEx(trxName);
		} finally {
			if (!crossTenantSet)
				PO.clearCrossTenantSafe();
		}
	}

	/**
	 * Update or insert new record.
	 * @param trxName transaction
	 * @throws AdempiereException if save fail
	 * @see #saveEx(String)
	 */
	public void saveEx(String trxName) throws AdempiereException
	{
		set_TrxName(trxName);
		saveEx();
	}

	/**
	 * 	Is there changes to be saved?
	 *	@return true if record changed
	 */
	public boolean is_Changed()
	{
		int size = get_ColumnCount();
		for (int i = 0; i < size; i++)
		{
			// Test if the column has changed - teo_sarca [ 1704828 ]
			if (is_ValueChanged(i))
				return true;
		}
		if (m_custom != null && m_custom.size() > 0)
			return true; // there are custom columns modified
		return false;
	}	//	is_Change

	/**
	 * 	Called before Save for Pre-Save Operation.<br/>
	 *  Default implementation is nop, to be implemented in sub-classes that needed it.
	 * 	@param newRecord true if it is a new record
	 *	@return true if record can be saved
	 */
	protected boolean beforeSave(boolean newRecord)
	{
		return true;
	}	//	beforeSave

	/**
	 * 	Called after Save for Post-Save Operation.<br/>
	 *  Default implementation is nop, to be implemented in sub-classes that needed it.
	 * 	@param newRecord true if it is a new record
	 *	@param success true if save operation was success
	 *	@return if save was a success
	 */
	protected boolean afterSave (boolean newRecord, boolean success)
	{
		return success;
	}	//	afterSave

	/**
	 * 	Update Record
	 * 	@return true if updated
	 */
	protected boolean saveUpdate()
	{
		boolean ok = doUpdate(isLogSQLScript());
		
		return saveFinish (false, ok);
	}   //  saveUpdate

	/**
	 * Is log SQL migration script.
	 * @return true if sql migration script should be logged for changes to this PO instance
	 */
	private boolean isLogSQLScript() {
		return Env.isLogMigrationScript(p_info.getTableName());
	}

	/**
	 * Perform DB update
	 * @param withValues true to create statement with column values, false to use parameter binding (i.e with ?)
	 * @return true if success
	 */
	private boolean doUpdate(boolean withValues) {
		//params for insert statement
		List<Object> params = new ArrayList<Object>();

		String where = withValues && get_ID() > MTable.MAX_OFFICIAL_ID ? get_WhereClause(true, get_ValueAsString(getUUIDColumnName())) : get_WhereClause(true);
		
		List<Object> optimisticLockingParams = new ArrayList<Object>();
		if (is_UseOptimisticLocking() && m_optimisticLockingColumns != null && m_optimisticLockingColumns.length > 0)
		{
			StringBuilder builder = new StringBuilder(where);
			addOptimisticLockingClause(optimisticLockingParams, builder);
			where = builder.toString();
		}
		//
		boolean changes = false;
		StringBuilder sql = new StringBuilder ("UPDATE ");
		sql.append(p_info.getTableName()).append( " SET ");
		boolean updated = false;
		boolean updatedBy = false;
		lobReset();

		//	Change Log
		MSession session = MSession.get (p_ctx);
		if (session == null)
			log.fine("No Session found");
		int AD_ChangeLog_ID = 0;

		//uuid secondary key - when updating, if the record doesn't have UUID, assign one
		int uuidIndex = p_info.getColumnIndex(getUUIDColumnName());
		if (uuidIndex >= 0)
		{
			String value = (String)get_Value(uuidIndex);
			if (p_info.getColumn(uuidIndex).FieldLength == 36 && (value == null || value.length() == 0))
			{
				UUID uuid = UUID.randomUUID();
				set_ValueNoCheck(p_info.getColumnName(uuidIndex), uuid.toString());
			}
		}

		int size = get_ColumnCount();
		for (int i = 0; i < size; i++)
		{
			Object value = m_newValues[i];
			if (value == null
				|| p_info.isVirtualColumn(i))
				continue;
			//  we have a change
			Class<?> c = p_info.getColumnClass(i);
			int dt = p_info.getColumnDisplayType(i);
			String columnName = p_info.getColumnName(i);
			//
			//	updated/by
			if (columnName.equals("UpdatedBy"))
			{
				if (updatedBy)	//	explicit
					continue;
				updatedBy = true;
			}
			else if (columnName.equals("Updated"))
			{
				if (updated)
					continue;
				updated = true;
			}
			if (DisplayType.isLOB(dt))
			{
				lobAdd (value, i, dt);
				//	If no changes set UpdatedBy explicitly to ensure commit of lob
				if (!changes && !updatedBy)
				{
					int AD_User_ID = Env.getContextAsInt(p_ctx, Env.AD_USER_ID);
					set_ValueNoCheck("UpdatedBy", Integer.valueOf(AD_User_ID));
					sql.append("UpdatedBy=").append(AD_User_ID);
					changes = true;
					updatedBy = true;
				}
				continue;
			}
			//	Update Document No
			if (columnName.equals("DocumentNo"))
			{
				String strValue = (String)value;
				if (strValue.startsWith("<") && strValue.endsWith(">"))
				{
					value = null;
					int AD_Client_ID = getAD_Client_ID();
					int index = p_info.getColumnIndex("C_DocTypeTarget_ID");
					if (index == -1)
						index = p_info.getColumnIndex("C_DocType_ID");
					if (index != -1)		//	get based on Doc Type (might return null)
						value = DB.getDocumentNo(get_ValueAsInt(index), m_trxName, false, this);
					if (value == null)	//	not overwritten by DocType and not manually entered
						value = DB.getDocumentNo(AD_Client_ID, p_info.getTableName(), m_trxName, this);
				}
				else
					if (log.isLoggable(Level.INFO)) log.info("DocumentNo updated: " + m_oldValues[i] + " -> " + value);
			}

			if (changes)
				sql.append(", ");
			changes = true;
			sql.append(DB.getDatabase().quoteColumnName(columnName)).append("=");

			if (withValues)
			{
				//  values
				if (value == Null.NULL)
					sql.append("NULL");
				else if (value instanceof Integer && "Record_ID".equalsIgnoreCase(columnName))
				{
					Integer idValue = (Integer) value;
					if (idValue <= MTable.MAX_OFFICIAL_ID) 
					{
						sql.append(value);
					}
					else if (p_info.getColumnIndex("AD_Table_ID") >= 0)
					{
						int tableId = get_ValueAsInt("AD_Table_ID");
						if (tableId > 0)
						{
							MTable refTable = MTable.get(Env.getCtx(), tableId);
							String refTableName = refTable.getTableName();
							String refKeyColumnName = refTable.getKeyColumns()[0];
							String refUUColumnName = MTable.getUUIDColumnName(refTableName);
							String refUUValue = DB.getSQLValueString(get_TrxName(), "SELECT " + refUUColumnName + " FROM "
									+ refTableName + " WHERE " + refKeyColumnName + "=?", (Integer)value);
							sql.append("toRecordId('"+ refTableName + "','" + refUUValue + "')");
						}
						else
						{
							sql.append(value);
						}
					}
					else
					{
						sql.append(value);
					}
				}
				else if (value instanceof Integer && p_info.isColumnLookup(i))
				{
					Integer idValue = (Integer) value;
					if (idValue <= MTable.MAX_OFFICIAL_ID) 
					{
						sql.append(value);
					}
					else
					{
						MColumn col = MColumn.get(p_info.getAD_Column_ID(columnName));
						String refTableName = col.getReferenceTableName();
						MTable refTable = MTable.get(Env.getCtx(), refTableName);
						String refKeyColumnName = refTable.getKeyColumns()[0];
						String refUUColumnName = MTable.getUUIDColumnName(refTableName);
						String refUUValue = DB.getSQLValueString(get_TrxName(), "SELECT " + refUUColumnName + " FROM "
								+ refTableName + " WHERE " + refKeyColumnName + "=?", (Integer)value);
						sql.append("toRecordId('"+ refTableName + "','" + refUUValue + "')");
					}
				}
				else if (value instanceof Integer || value instanceof BigDecimal)
					sql.append(value);
				else if (c == Boolean.class)
				{
					boolean bValue = false;
					if (value instanceof Boolean)
						bValue = ((Boolean)value).booleanValue();
					else
						bValue = "Y".equals(value);
					sql.append(encrypt(i,bValue ? "'Y'" : "'N'"));
				}
				else if (value instanceof Timestamp)
					sql.append(DB.TO_DATE((Timestamp)encrypt(i,value),p_info.getColumnDisplayType(i) == DisplayType.Date));
				else {
					if (value.toString().length() == 0) {
						// [ 1722057 ] Encrypted columns throw error if saved as null
						// don't encrypt NULL
						sql.append(DB.TO_STRING(value.toString()));
					} else {
						sql.append(encrypt(i,DB.TO_STRING(value.toString())));
					}
				}
			} 
			else
			{
				if (value instanceof Timestamp && dt == DisplayType.Date)
					sql.append("trunc(cast(? as date))");
				else if (dt == DisplayType.JSON)
					sql.append(DB.getJSONCast());
				else
					sql.append("?");
				
				if (value == Null.NULL)
				{
					params.add(null);
				}
				else if (c == Boolean.class)
				{
					boolean bValue = false;
					if (value instanceof Boolean)
						bValue = ((Boolean)value).booleanValue();
					else
						bValue = "Y".equals(value);
					params.add(encrypt(i,bValue ? "Y" : "N"));
				}
				else if (c == String.class)
				{
					if (value.toString().length() == 0) {
						// [ 1722057 ] Encrypted columns throw error if saved as null
						// don't encrypt NULL
						params.add(null);
					} else {
						params.add(encrypt(i,value));
					}
				} 
				else
				{
					params.add(value);
				}					
			}

			//	Change Log	- Only
			if (session != null
				&& p_info.isAllowLogging(i)		//	logging allowed
				&& !p_info.isEncrypted(i)		//	not encrypted
				&& !p_info.isVirtualColumn(i)	//	no virtual column
				&& !"Password".equals(columnName)
				&& !session.isSkipChangeLogForUpdate(get_TableName())
				)
			{
				Object oldV = m_oldValues[i];
				Object newV = value;
				if (oldV != null && oldV == Null.NULL)
					oldV = null;
				if (newV != null && newV == Null.NULL)
					newV = null;
				// change log on update
				MChangeLog cLog = session.changeLog (
					m_trxName, AD_ChangeLog_ID,
					p_info.getAD_Table_ID(), p_info.getColumn(i).AD_Column_ID,
					(m_IDs.length == 1 ? get_ID() : 0), get_UUID(), getAD_Client_ID(), getAD_Org_ID(), oldV, newV, MChangeLog.EVENTCHANGELOG_Update);
				if (cLog != null)
					AD_ChangeLog_ID = cLog.getAD_ChangeLog_ID();
			}
		}	//   for all fields

		//	Custom Columns (cannot be logged as no column)
		if (m_custom != null)
		{
			Iterator<String> it = m_custom.keySet().iterator();
			while (it.hasNext())
			{
				if (changes)
					sql.append(", ");
				changes = true;
				//
				String column = (String)it.next();
				String value = (String)m_custom.get(column);
				int index = p_info.getColumnIndex(column);
				if (withValues)
				{
					sql.append(column).append("=").append(encrypt(index,value));
				}
				else
				{
					sql.append(column).append("=?");
					if (value == null || value.toString().length() == 0)
					{
						params.add(null);
					} 
					else
					{
						params.add(encrypt(index,value));
					}
				}
			}
			m_custom = null;
		}

		//	Something changed
		if (changes)
		{
			if (m_trxName == null) {
				if (log.isLoggable(Level.FINE)) log.fine(p_info.getTableName() + "." + where);
			} else {
				if (log.isLoggable(Level.FINE)) log.fine("[" + m_trxName + "] - " + p_info.getTableName() + "." + where);
			}
			if (!updated)	//	Updated not explicitly set
			{
				Timestamp now = new Timestamp(System.currentTimeMillis());
				set_ValueNoCheck("Updated", now);
				if (withValues)
				{
					sql.append(",Updated=").append(DB.TO_DATE(now, false));
				}
				else
				{
					sql.append(",Updated=?");
					params.add(now);
				}
			}
			if (!updatedBy)	//	UpdatedBy not explicitly set
			{
				int AD_User_ID = Env.getContextAsInt(p_ctx, Env.AD_USER_ID);
				set_ValueNoCheck("UpdatedBy", Integer.valueOf(AD_User_ID));
				if (withValues)
				{
					sql.append(",UpdatedBy=").append(AD_User_ID);
				}
				else
				{
					sql.append(",UpdatedBy=?");
					params.add(AD_User_ID);
				}
			}
			sql.append(" WHERE ").append(where);

			if (log.isLoggable(Level.FINEST)) log.finest(sql.toString());
			
			if (is_UseOptimisticLocking() && optimisticLockingParams.size() > 0)
				params.addAll(optimisticLockingParams);
			
			int no = 0;
			if (isUseTimeoutForUpdate())
				no = withValues ? DB.executeUpdateEx(sql.toString(), m_trxName, QUERY_TIME_OUT)
								: DB.executeUpdateEx(sql.toString(), params.toArray(), m_trxName, QUERY_TIME_OUT);
			else
				no = withValues ? DB.executeUpdate(sql.toString(), m_trxName)
						 		: DB.executeUpdate(sql.toString(), params.toArray(), false, m_trxName);
			boolean ok = no == 1;
			if (ok)
				ok = lobSave();
			else
			{
				if (CLogger.peekError() == null) {
					if (m_trxName == null)
						log.saveError("SaveError", "Update return " + no + " instead of 1"
							+ " - " + p_info.getTableName() + "." + where);
					else
						log.saveError("SaveError", "Update return " + no + " instead of 1"
							+ " - [" + m_trxName + "] - " + p_info.getTableName() + "." + where);
				} else {
					String msg = "Not updated - ";
					if (CLogMgt.isLevelFiner())
						msg += sql.toString();
					else
						msg += get_TableName();
					if (m_trxName == null)
						log.log(Level.WARNING, msg);
					else
						log.log(Level.WARNING, "[" + m_trxName + "]" + msg);
				}
			}
			return ok;
		}
		else
		{
			// nothing changed, so OK
			return true;
		}
	}
	
	/**
	 * Add where clause for optimistic locking
	 * @param optimisticLockingParams
	 * @param where
	 */
	private void addOptimisticLockingClause(List<Object> optimisticLockingParams, StringBuilder where) {
		for(String oc : m_optimisticLockingColumns)
		{
			int index = get_ColumnIndex(oc); 
			if (index >= 0)
			{
				Class<?> c = p_info.getColumnClass(index);
				int dt = p_info.getColumnDisplayType(index);
				if (DisplayType.isLOB(dt))
					continue;
				Object value = get_ValueOld(oc);
				if (value == null)
				{
					where.append(" AND ").append(oc).append(" IS NULL ");
				}
				else if (value instanceof Timestamp)
				{
					if (dt == DisplayType.Date)
						where.append(" AND ").append(oc).append(" = trunc(cast(? as date))");
					else
						where.append(" AND ").append(oc).append(" = ? ");
					optimisticLockingParams.add(value);
				}
				else if (c == Boolean.class)
				{
					where.append(" AND ").append(oc).append(" = ? ");
					boolean bValue = false;
					if (value instanceof Boolean)
						bValue = ((Boolean)value).booleanValue();
					else
						bValue = "Y".equals(value);
					optimisticLockingParams.add(encrypt(index,bValue ? "Y" : "N"));
				}
				else if (c == String.class)
				{
					if (value.toString().length() == 0) {
						where.append(" AND ").append(oc).append(" = '' ");
					} else {
						where.append(" AND ").append(oc).append(" = ? ");
						optimisticLockingParams.add(encrypt(index,value));
					}
				}
				else
				{
					where.append(" AND ").append(oc).append(" = ? ");
					optimisticLockingParams.add(value);
				}
				
			}
		}
	}

	/**
	 * Is this PO instance using optimistic locking
	 * @return true if optimistic locking is enable
	 */
	public boolean is_UseOptimisticLocking() {
		if (m_useOptimisticLocking != null)
			return m_useOptimisticLocking;
		else
			return SystemProperties.isOptimisticLocking();
	}
	
	/**
	 * Enable/disable optimistic locking
	 * @param enable
	 */
	public void set_UseOptimisticLocking(boolean enable) {
		m_useOptimisticLocking = enable;
	}
	
	/**
	 * Get columns for optimistic locking
	 * @return optimistic locking columns
	 */
	public String[] get_OptimisticLockingColumns() {
		return m_optimisticLockingColumns;
	}

	/**
	 * Set columns use for optimistic locking (auto add to where clause for update
	 * and delete).
	 * @param columns
	 */
	public void set_OptimisticLockingColumns(String[] columns) {
		m_optimisticLockingColumns = columns;
	}
	
	/**
	 * Is using statement timeout for update operation
	 * @return true if statement timeout is use
	 */
	private boolean isUseTimeoutForUpdate() {
		return SystemProperties.isUseTimeoutForUpdate()
			&& DB.getDatabase().isQueryTimeoutSupported();
	}

	/**
	 *  Insert New Record
	 *  @return true if new record inserted
	 */
	private boolean saveNew()
	{
		//  Set ID for single key - Multi-Key values need explicitly be set previously
		if (m_IDs.length == 1 && p_info.hasKeyColumn()
			&& m_KeyColumns[0].endsWith("_ID") && (Env.isUseCentralizedId(p_info.getTableName()) || !isLogSQLScript()))	//	AD_Language, EntityType
		{
			int no = saveNew_getID();
			if (no <= 0)
				no = DB.getNextID(getAD_Client_ID(), p_info.getTableName(), m_trxName);
			// the primary key is not overwrite with the local sequence
			if (isReplication())
			{
				if (get_ID() > 0)
				{
					no = get_ID();
				}
			}
			if (no <= 0)
			{
				log.severe("No NextID (" + no + ")");
				return saveFinish (true, false);
			}
			m_IDs[0] = Integer.valueOf(no);
			set_ValueNoCheck(m_KeyColumns[0], m_IDs[0]);
			saveNew_afterSetID();
		}
		//uuid secondary key
		int uuidIndex = p_info.getColumnIndex(getUUIDColumnName());
		if (uuidIndex >= 0)
		{
			String value = (String)get_Value(uuidIndex);
			if (p_info.getColumn(uuidIndex).FieldLength == 36 && (value == null || value.length() == 0))
			{
				UUID uuid = UUID.randomUUID();
				set_ValueNoCheck(p_info.getColumnName(uuidIndex), uuid.toString());
			}
		}
		if (m_trxName == null) {
			if (log.isLoggable(Level.FINE)) log.fine(p_info.getTableName() + " - " + get_WhereClause(true));
		} else {
			if (log.isLoggable(Level.FINE)) log.fine("[" + m_trxName + "] - " + p_info.getTableName() + " - " + get_WhereClause(true));
		}

		//	Set new DocumentNo
		String columnName = "DocumentNo";
		int index = p_info.getColumnIndex(columnName);
		if (index != -1)
		{
			String value = (String)get_Value(index);
			if (value != null && value.startsWith("<") && value.endsWith(">"))
				value = null;
			if (value == null || value.length() == 0)
			{
				int dt = p_info.getColumnIndex("C_DocTypeTarget_ID");
				if (dt == -1)
					dt = p_info.getColumnIndex("C_DocType_ID");
				if (dt != -1)		//	get based on Doc Type (might return null)
					value = DB.getDocumentNo(get_ValueAsInt(dt), m_trxName, false, this);
				if (value == null)	//	not overwritten by DocType and not manually entered
					value = DB.getDocumentNo(getAD_Client_ID(), p_info.getTableName(), m_trxName, this);
				set_ValueNoCheck(columnName, value);
			}
		}
		// ticket 1007459 - exclude M_AttributeInstance from filling Value column
		// IDEMPIERE-4224 - exclude AD_TableAttribute from filling Value column
		if (!MAttributeInstance.Table_Name.equals(get_TableName())
			&& !MTableAttribute.Table_Name.equals(get_TableName())) {
			//	Set empty Value
			columnName = "Value";
			index = p_info.getColumnIndex(columnName);
			if (index != -1)
			{
				if (!p_info.isVirtualColumn(index))
				{
					String value = (String)get_Value(index);
					if (value == null || value.length() == 0)
					{
						value = DB.getDocumentNo (getAD_Client_ID(), p_info.getTableName(), m_trxName, this);
						set_ValueNoCheck(columnName, value);
					}
				}
			}
		}

		boolean ok = doInsert(isLogSQLScript());
		return saveFinish (true, ok);
	}   //  saveNew

	/**
	 * Perform insert operation
	 * @param withValues true to create statement with column values, false to use parameter binding (i.e with ?)
	 * @return true if success
	 */
	private boolean doInsert(boolean withValues) {
		lobReset();

		//	Change Log
		MSession session = MSession.get (p_ctx);
		if (session == null)
			log.fine("No Session found");
		int AD_ChangeLog_ID = 0;

		//params for insert statement
		List<Object> params = new ArrayList<Object>();
				
		//	SQL
		StringBuilder sqlInsert = new StringBuilder();
		AD_ChangeLog_ID = buildInsertSQL(sqlInsert, withValues, params, session, AD_ChangeLog_ID, false, null);
		//
		int no = withValues ? DB.executeUpdate(sqlInsert.toString(), m_trxName) 
							: DB.executeUpdate(sqlInsert.toString(), params.toArray(), false, m_trxName);
		boolean ok = no == 1;
		if (ok)
		{
			if (withValues && m_IDs.length == 1 && p_info.hasKeyColumn()
					&& m_KeyColumns[0].endsWith("_ID") && !Env.isUseCentralizedId(p_info.getTableName()))
			{
				StringBuilder sql = new StringBuilder("SELECT ").append(m_KeyColumns[0]).append(" FROM ").append(p_info.getTableName()).append(" WHERE ").append(getUUIDColumnName()).append("=?");
				int id = DB.getSQLValueEx(get_TrxName(), sql.toString(), get_ValueAsString(getUUIDColumnName()));
				m_IDs[0] = Integer.valueOf(id);
				set_ValueNoCheck(m_KeyColumns[0], m_IDs[0]);
			}

			if (withValues && !Env.isUseCentralizedId(p_info.getTableName()))
			{
				int ki = p_info.getColumnIndex(m_KeyColumns[0]);
				//	Change Log	- Only
				String insertLog = MSysConfig.getValue(MSysConfig.SYSTEM_INSERT_CHANGELOG, "N", getAD_Client_ID());
				if (   session != null
					&& p_info.isAllowLogging(ki)		//	logging allowed
					&& !p_info.isEncrypted(ki)		//	not encrypted
					&& !p_info.isVirtualColumn(ki)	//	no virtual column
					&& !"Password".equals(p_info.getColumnName(ki))
					&& (   insertLog.equalsIgnoreCase("Y")
						|| (   insertLog.equalsIgnoreCase("K") 
							&& (   p_info.getColumn(ki).IsKey
								|| (   !p_info.hasKeyColumn() 
									&& p_info.getColumn(ki).ColumnName.equals(PO.getUUIDColumnName(p_info.getTableName())))))))
				{
					int id = (m_IDs.length == 1 ? get_ID() : 0);
					// change log on new
					MChangeLog cLog = session.changeLog (
							m_trxName, AD_ChangeLog_ID,
							p_info.getAD_Table_ID(), p_info.getColumn(ki).AD_Column_ID,
							(m_IDs.length == 1 ? get_ID() : 0), get_UUID(), getAD_Client_ID(), getAD_Org_ID(), null, (id == 0 ? get_UUID() : id), MChangeLog.EVENTCHANGELOG_Insert);
					if (cLog != null)
						AD_ChangeLog_ID = cLog.getAD_ChangeLog_ID();
				}
			}
			ok = lobSave();
			if (!load(m_trxName))		//	re-read Info
			{
				if (m_trxName == null)
					log.log(Level.SEVERE, "reloading");
				else
					log.log(Level.SEVERE, "[" + m_trxName + "] - reloading");
				ok = false;
			}
		}
		else
		{
			String msg = "Not inserted - ";
			if (CLogMgt.isLevelFiner())
				msg += sqlInsert.toString();
			else
				msg += get_TableName();
			if (m_trxName == null)
				log.log(Level.WARNING, msg);
			else
				log.log(Level.WARNING, "[" + m_trxName + "]" + msg);
		}
		return ok;
	}

	/**
	 * Export data as insert SQL statement
	 * @param database 
	 * @return SQL insert statement
	 */
	public String toInsertSQL(String database) 
	{
		StringBuilder sqlInsert = new StringBuilder();
		buildInsertSQL(sqlInsert, true, null, null, 0, true, database);
		return sqlInsert.toString();
	}
	
	/**
	 * Build insert SQL statement and capture change log
	 * @param sqlInsert
	 * @param withValues true to create statement with column values, false to use parameter binding (i.e with ?)
	 * @param params statement parameters when withValues is false
	 * @param session to capture change log. null when call from toInsertSQL (i.e to build sql only, not for real insert to DB)
	 * @param AD_ChangeLog_ID initial change log id
	 * @param generateScriptOnly true if it is to generate sql script only, false for real DB insert
	 * @return last AD_ChangeLog_ID
	 */
	protected int buildInsertSQL(StringBuilder sqlInsert, boolean withValues, List<Object> params, MSession session,
			int AD_ChangeLog_ID, boolean generateScriptOnly, String database) {
		sqlInsert.append("INSERT INTO ");
		sqlInsert.append(p_info.getTableName()).append(" (");
		StringBuilder sqlValues = new StringBuilder(") VALUES (");
		int size = get_ColumnCount();
		boolean doComma = false;
		Map<String, String> oracleBlobSQL = new HashMap<String, String>();
		for (int i = 0; i < size; i++)
		{
			Object value = get_Value(i);
			//	Don't insert NULL values (allows Database defaults)
			if (value == null
				|| p_info.isVirtualColumn(i))
				continue;

			//	Display Type
			int dt = p_info.getColumnDisplayType(i);
			if (DisplayType.isLOB(dt))
			{
				lobAdd (value, i, dt);
			}

			//do not export secure column
			if (generateScriptOnly)
			{
				if (p_info.isEncrypted(i) || p_info.isSecure(i) || "Password".equalsIgnoreCase(p_info.getColumnName(i)))
					continue;
			}
			
			//	** add column **
			if (doComma)
			{
				sqlInsert.append(",");
				sqlValues.append(",");
			}
			else
				doComma = true;
			sqlInsert.append(DB.getDatabase().quoteColumnName(p_info.getColumnName(i)));
			//
			//  Based on class of definition, not class of value
			Class<?> c = p_info.getColumnClass(i);
			if (withValues) 
			{				
				try
				{
					if (m_IDs.length == 1 && p_info.hasKeyColumn()
							&& m_KeyColumns[0].endsWith("_ID") && m_KeyColumns[0].equals(p_info.getColumnName(i)) && (generateScriptOnly || !Env.isUseCentralizedId(p_info.getTableName())))
					{
						if (generateScriptOnly && get_ID() > 0 && get_ID() <= MTable.MAX_OFFICIAL_ID)
						{
							sqlValues.append(value);
						}
						else
						{
							MSequence sequence = MSequence.get(Env.getCtx(), p_info.getTableName(), get_TrxName(), true);
							sqlValues.append("nextidfunc("+sequence.getAD_Sequence_ID()+",'N')");
						}
					}
					else if (c == Object.class) //  may have need to deal with null values differently
						sqlValues.append (saveNewSpecial (value, i));
					else if (value == null || value.equals (Null.NULL))
						sqlValues.append ("NULL");
					else if (value instanceof Integer && "Record_ID".equalsIgnoreCase(p_info.getColumnName(i)))
					{
						Integer idValue = (Integer) value;
						if (idValue <= MTable.MAX_OFFICIAL_ID) 
						{
							sqlValues.append(value);
						}
						else if (p_info.getColumnIndex("AD_Table_ID") >= 0)
						{
							int tableId = get_ValueAsInt("AD_Table_ID");
							if (tableId > 0)
							{
								MTable refTable = MTable.get(Env.getCtx(), tableId);
								String refTableName = refTable.getTableName();
								String refKeyColumnName = refTable.getKeyColumns()[0];
								String refUUColumnName = MTable.getUUIDColumnName(refTableName);
								String refUUValue = DB.getSQLValueString(get_TrxName(), "SELECT " + refUUColumnName + " FROM "
										+ refTableName + " WHERE " + refKeyColumnName + "=?", (Integer)value);
								sqlValues.append("toRecordId('"+ refTableName + "','" + refUUValue + "')");
							}
							else
							{
								sqlValues.append(value);
							}
						}
						else
						{
							sqlValues.append(value);
						}
					}
					else if (value instanceof Integer && p_info.isColumnLookup(i))
					{
						Integer idValue = (Integer) value;
						if (idValue <= MTable.MAX_OFFICIAL_ID) 
						{
							sqlValues.append(value);
						}
						else
						{
							MColumn col = MColumn.get(p_info.getAD_Column_ID(p_info.getColumnName(i)));
							String refTableName = col.getReferenceTableName();
							MTable refTable = MTable.get(Env.getCtx(), refTableName);
							String refKeyColumnName = refTable.getKeyColumns()[0];
							String refUUColumnName = MTable.getUUIDColumnName(refTable.getTableName());
							String refUUValue = DB.getSQLValueString(get_TrxName(), "SELECT " + refUUColumnName + " FROM "
									+ refTableName + " WHERE " + refKeyColumnName + "=?", (Integer)value);
							sqlValues.append("toRecordId('"+ refTableName + "','" + refUUValue + "')");
						}
					}
					else if (value instanceof Integer || value instanceof BigDecimal)
						sqlValues.append (value);
					else if (c == Boolean.class)
					{
						boolean bValue = false;
						if (value instanceof Boolean)
							bValue = ((Boolean)value).booleanValue();
						else
							bValue = "Y".equals(value);
						sqlValues.append (encrypt(i,bValue ? "'Y'" : "'N'"));
					}
					else if (value instanceof Timestamp)
						sqlValues.append (DB.TO_DATE ((Timestamp)encrypt(i,value), p_info.getColumnDisplayType (i) == DisplayType.Date));
					else if (c == String.class)
						sqlValues.append (encrypt(i,DB.TO_STRING ((String)value)));
					else if (DisplayType.isLOB(dt))
					{
						if(database!=null && MSysConfig.getBooleanValue(MSysConfig.EXPORT_BLOB_COLUMN_FOR_INSERT, true, getAD_Client_ID())) 
						{
							String blobSQL = Database.getDatabase(database).TO_Blob((byte[]) value);
							// Oracle size limit for one SQL statement
							if (blobSQL != null && database.equals(Database.DB_ORACLE) && blobSQL.length() > 2048)
							{
								oracleBlobSQL.put(p_info.getColumnName(i), blobSQL);
								blobSQL = p_info.isColumnMandatory(i) ? "'0'" : null;
							}
							sqlValues.append (blobSQL);
						}
						else if (p_info.isColumnMandatory(i))
                        {
                            sqlValues.append("'0'");        //    no db dependent stuff here -- at this point value is known to be not null
                        }
                        else
                        {
                            sqlValues.append("null");
                        }
					}
					else
						sqlValues.append (saveNewSpecial (value, i));
				}
				catch (Exception e)
				{
					String msg = "";
					if (m_trxName != null)
						msg = "[" + m_trxName + "] - ";
					msg += p_info.toString(i)
						+ " - Value=" + value
						+ "(" + (value==null ? "null" : value.getClass().getName()) + ")";
					log.log(Level.SEVERE, msg, e);
					throw new DBException(e);	//	fini
				}
			}
			else
			{				
				if (value instanceof Timestamp && dt == DisplayType.Date)
					sqlValues.append("trunc(cast(? as date))");
				else if (dt == DisplayType.JSON)
					sqlValues.append(DB.getJSONCast());
				else
					sqlValues.append("?");
							
				if (DisplayType.isLOB(dt))
				{
					if (p_info.isColumnMandatory(i))
					{
						if (dt == DisplayType.Binary)
							params.add(new byte[] {0}); // -- at this point value is known to be not null
						else
							params.add(""); // -- at this point value is known to be not null
					}
					else
					{
						params.add(null);
					}
				}
				else if (value == null || value.equals (Null.NULL))
				{
					params.add(null);
				}
				else if (c == Boolean.class)
				{
					boolean bValue = false;
					if (value instanceof Boolean)
						bValue = ((Boolean)value).booleanValue();
					else
						bValue = "Y".equals(value);
					params.add(encrypt(i,bValue ? "Y" : "N"));
				}
				else if (c == String.class)
				{
					if (value.toString().length() == 0)
					{
						params.add(null);
					}
					else
					{
						params.add(encrypt(i,value));
					}
				}
				else
				{
					params.add(value);
				}
			}

			if (session != null && (!withValues || Env.isUseCentralizedId(p_info.getTableName())))
			{
				//	Change Log	- Only
				String insertLog = MSysConfig.getValue(MSysConfig.SYSTEM_INSERT_CHANGELOG, "N", getAD_Client_ID());
				if (!generateScriptOnly && session != null
					&& p_info.isAllowLogging(i)		//	logging allowed
					&& !p_info.isEncrypted(i)		//	not encrypted
					&& !p_info.isVirtualColumn(i)	//	no virtual column
					&& !"Password".equals(p_info.getColumnName(i))
					&& (insertLog.equalsIgnoreCase("Y")
							|| (insertLog.equalsIgnoreCase("K")
								&& (   p_info.getColumn(i).IsKey
									|| (   !p_info.hasKeyColumn()
										&& p_info.getColumn(i).ColumnName.equals(PO.getUUIDColumnName(p_info.getTableName()))))))
					)
				{
					// change log on new
					MChangeLog cLog = session.changeLog (
							m_trxName, AD_ChangeLog_ID,
							p_info.getAD_Table_ID(), p_info.getColumn(i).AD_Column_ID,
							(m_IDs.length == 1 ? get_ID() : 0), get_UUID(), getAD_Client_ID(), getAD_Org_ID(), null, value, MChangeLog.EVENTCHANGELOG_Insert);
					if (cLog != null)
						AD_ChangeLog_ID = cLog.getAD_ChangeLog_ID();
				}
			}
		}
		//	Custom Columns
		if (m_custom != null)
		{
			Iterator<String> it = m_custom.keySet().iterator();
			while (it.hasNext())
			{
				String column = (String)it.next();
				int index = p_info.getColumnIndex(column);
				String value = (String)m_custom.get(column);
				if (value == null)
					continue;
				if (doComma)
				{
					sqlInsert.append(",");
					sqlValues.append(",");
				}
				else
					doComma = true;
				sqlInsert.append(column);
				if (withValues)
				{
					sqlValues.append(encrypt(index, value));
				}
				else
				{
					sqlValues.append("?");
					if (value == null || value.toString().length() == 0)
					{
						params.add(null);
					}
					else
					{
						params.add(encrypt(index, value));
					}
				}
			}
			m_custom = null;
		}
		sqlInsert.append(sqlValues)
			.append(")");
		
		// Use pl/sql block for Oracle blob insert that's > 2048 bytes
		if (!oracleBlobSQL.isEmpty()) 
		{
			sqlInsert.append("\n;");
			for(String column : oracleBlobSQL.keySet())
			{
				sqlInsert.append("\n\n");				
				String blobSQL = oracleBlobSQL.get(column);
				int hexDataStart = blobSQL.indexOf("'");
				int hexDataEnd = blobSQL.indexOf("'", hexDataStart+1);
				String functionStart = blobSQL.substring(0, hexDataStart);
				String hexData = blobSQL.substring(hexDataStart+1, hexDataEnd);
				String functionEnd = blobSQL.substring(hexDataEnd+1);
				int remaining = hexData.length();
				int lineSize = 2048;
				sqlInsert.append("DECLARE\n")
					.append("   lob_out blob;\n")
					.append("BEGIN\n")
					.append("   UPDATE ").append(p_info.getTableName())
					.append(" SET ").append(column).append("=EMPTY_BLOB()\n")
					.append("   WHERE ").append(getUUIDColumnName()).append("=")
					.append("'").append(get_UUID()).append("';\n")
					.append("   SELECT ").append(column).append(" INTO lob_out\n")
					.append("   FROM ").append(p_info.getTableName()).append("\n")
					.append("   WHERE ").append(getUUIDColumnName()).append("=")
					.append("'").append(get_UUID()).append("'\n")
					.append("   FOR UPDATE;\n");
				// Split hex encoded text into 2048 bytes block
				int index = 0;				
				while (remaining > 0) 
				{
					sqlInsert.append("   dbms_lob.append(lob_out, ").append(functionStart).append("'");
					String data = remaining > lineSize ? hexData.substring(index, index+lineSize) : hexData.substring(index);
					sqlInsert.append(data).append("'").append(functionEnd).append(");\n");
					remaining = remaining > lineSize ? remaining - lineSize : 0;
					index = index + lineSize;
				}
				sqlInsert.append("END;\n/");
			}
		}
		return AD_ChangeLog_ID;
	}

	/**
	 * 	Get ID for new record during save.<br/>
	 * 	You can overwrite this to explicitly set the ID.
	 *	@return ID to be used or 0 for default logic
	 */
	protected int saveNew_getID()
	{
		if (get_ID() > 0 && get_ID() < 999999) // 2Pack assigns official ID's when importing
			return get_ID();
		return 0;
	}	//	saveNew_getID

	/**
	 * Call after ID have been assigned for new record.<br/>
	 * Default implementation is nop, to be implemented in sub-classes that needed it.
	 */
	protected void saveNew_afterSetID()
	{
		
	}
	
	/**
	 * 	Create Single/Multi Key Where Clause
	 * 	@param withValues if true uses column values, otherwise uses parameter binding (i.e with ?)
	 * 	@return where clause
	 */
	public String get_WhereClause (boolean withValues) {
		return get_WhereClause(withValues,null);
	}

	/**
	 * 	Create Where Clause with UUID. If UUID is null, fall back to single/multi key where clause.
	 * 	@param withValues if true uses column values, otherwise uses parameter binding (i.e with ?)
	 *  @param uuID RecordUU
	 * 	@return where clause
	 */
	public String get_WhereClause (boolean withValues, String uuID)
	{
		StringBuilder sb = new StringBuilder();

		if (!Util.isEmpty(uuID, true))
		{
			sb.append(getUUIDColumnName()).append("=");
			if (withValues)
				sb.append(DB.TO_STRING(uuID));
			else
				sb.append("?");

			return sb.toString();
		}

		for (int i = 0; i < m_IDs.length; i++)
		{
			if (i != 0)
				sb.append(" AND ");
			sb.append(m_KeyColumns[i]).append("=");
			if (withValues)
			{
				if (m_KeyColumns[i].endsWith("_ID"))
					sb.append(m_IDs[i]);
				else if(m_IDs[i] instanceof Timestamp)
					sb.append(DB.TO_DATE((Timestamp)m_IDs[i], false));
				else {
					sb.append("'");
					if (m_IDs[i] instanceof Boolean) {
						if ((Boolean) m_IDs[i]) {
							sb.append("Y");
						} else {
							sb.append("N");
						}
					} else {
						sb.append(m_IDs[i]);
					}
					sb.append("'");
				}
			}
			else
				sb.append("?");
		}
		return sb.toString();
	}	//	getWhereClause

	/**
	 *  Save data for custom Java type that have no build in implementation.<br/>
	 *  To be extended by sub-classes (default implementation just call value.toString()).
	 *  @param value value to set
	 *  @param index column index
	 *  @return SQL code for INSERT VALUES clause
	 */
	protected String saveNewSpecial (Object value, int index)
	{
		String colName = p_info.getColumnName(index);
		String colClass = p_info.getColumnClass(index).toString();
		String colValue = value == null ? "null" : value.getClass().toString();

		log.log(Level.SEVERE, "Unknown class for column " + colName
			+ " (" + colClass + ") - Value=" + colValue);

		if (value == null)
			return "NULL";
		return value.toString();
	}   //  saveNewSpecial

	/**
	 * 	Encrypt data.
	 *	@param index column index
	 *	@param xx data to encrypt
	 *	@return encrypted data or xx if column is not encrypted
	 */
	private Object encrypt (int index, Object xx)
	{
		if (xx == null)
			return null;
		if (index != -1 && p_info.isEncrypted(index)) {
			return SecureEngine.encrypt(xx, getAD_Client_ID());
		}
		return xx;
	}	//	encrypt

	/**
	 * 	Decrypt data.
	 *	@param index column index
	 *	@param yy data to decrypt
	 *	@return decrypted data or yy if column is not encrypted
	 */
	private Object decrypt (int index, Object yy)
	{
		if (yy == null)
			return null;
		if (index != -1 && p_info.isEncrypted(index)) {
			return SecureEngine.decrypt(yy, getAD_Client_ID());
		}
		return yy;
	}	//	decrypt

	/**
	 * 	Delete Current Record
	 * 	@param force delete also processed records
	 * 	@return true if deleted
	 */
	public boolean delete (boolean force)
	{
		CLogger.resetLast();
		if (is_new())
			return true;

		if (!checkReadOnlySession())
			return false;
		checkImmutable();
		checkValidContext();
		checkCrossTenant(true);

		int AD_Table_ID = p_info.getAD_Table_ID();
		int Record_ID = get_ID();
		String Record_UU = get_UUID();

		if (!force)
		{
			int iProcessed = get_ColumnIndex("Processed");
			if  (iProcessed != -1)
			{
				Boolean processed = (Boolean)get_Value(iProcessed);
				if (processed != null && processed.booleanValue())
				{
					log.warning("Record processed");	//	CannotDeleteTrx
					log.saveError("Processed", "Processed", false);
					return false;
				}
			}	//	processed
		}	//	force

		// Carlos Ruiz - globalqss - IDEMPIERE-111
		// Check if the role has access to this client
		// Don't check role System as webstore works with this role - see IDEMPIERE-401
		if ((Env.getAD_Role_ID(getCtx()) != 0) && !MRole.getDefault().isClientAccess(getAD_Client_ID(), true))
		{
			log.warning("You cannot delete this record, role doesn't have access");
			log.saveError("AccessCannotDelete", "", false);
			return false;
		}

		Trx localTrx = null;
		Trx trx = null;
		Savepoint savepoint = null;
		boolean success = false;
		try
		{

			String localTrxName = m_trxName;
			if (localTrxName == null)
			{
				localTrxName = Trx.createTrxName("POdel");
				localTrx = Trx.get(localTrxName, true);
				localTrx.setDisplayName(getClass().getName()+ "_delete_ID" + get_ID());
				localTrx.getConnection();
				m_trxName = localTrxName;
			}
			else
			{
				trx = Trx.get(m_trxName, false);
				if (trx == null)
				{
					// Using a trx that was previously closed or never opened
					// Creating and starting the transaction right here, but please note
					// that this is not a good practice
					trx = Trx.get(m_trxName, true);
					log.severe("Transaction closed or never opened ("+m_trxName+") => starting now --> " + toString());
				}
			}
			
			try
			{
				// If not a localTrx we need to set a savepoint for rollback
				if (localTrx == null)
					savepoint = trx.setSavepoint(null);
				
				if (!beforeDelete())
				{
					log.warning("beforeDelete failed");
					if (localTrx != null) 
					{
						localTrx.rollback();
					}
					else if (savepoint != null)
					{
						try {
							trx.rollback(savepoint);
						} catch (SQLException e) {}
						savepoint = null;
					}
					return false;
				}
			}
			catch (Exception e)
			{
				log.log(Level.WARNING, "beforeDelete", e);
				String msg = DBException.getDefaultDBExceptionMessage(e);
				log.saveError(msg != null ? msg : "Error", e, false);
				if (localTrx != null) 
				{
					localTrx.rollback();
				}
				else if (savepoint != null)
				{
					try {
						trx.rollback(savepoint);
					} catch (SQLException e1) {}
					savepoint = null;
				}
				return false;
			}
			//	Delete Restrict AD_Table_ID/Record_ID (Requests, ..)
			String errorMsg = PO_Record.exists(AD_Table_ID, Record_ID, m_trxName);
			if (errorMsg == null && Record_UU != null)
				errorMsg = PO_Record.exists(AD_Table_ID, Record_UU, m_trxName);
			if (errorMsg != null)
			{
				log.saveError("CannotDelete", errorMsg);
				if (localTrx != null) 
				{
					localTrx.rollback();
				}
				else if (savepoint != null)
				{
					try {
						trx.rollback(savepoint);
					} catch (SQLException e) {}
					savepoint = null;
				}
				return false;
			}
			// Call ModelValidators TYPE_DELETE
			errorMsg = ModelValidationEngine.get().fireModelChange
				(this, isReplication() ? ModelValidator.TYPE_BEFORE_DELETE_REPLICATION : ModelValidator.TYPE_DELETE);
			setReplication(false); // @Trifon
			if (errorMsg != null)
			{
				log.saveError("Error", errorMsg);
				if (localTrx != null) 
				{
					localTrx.rollback();
				}
				else if (savepoint != null)
				{
					try {
						trx.rollback(savepoint);
					} catch (SQLException e) {}
					savepoint = null;
				}
				return false;
			}

			try 
			{
				//
				deleteTranslations(localTrxName);
				if (get_ColumnIndex("IsSummary") >= 0 && getTable().hasCustomTree()) {
					delete_Tree(MTree_Base.TREETYPE_CustomTable);
				}

				if (m_KeyColumns != null && m_KeyColumns.length == 1 && !getTable().isUUIDKeyTable()) {
					//delete cascade only for single key column record
					PO_Record.deleteModelCascade(p_info.getTableName(), Record_ID, localTrxName);
					//	Delete Cascade AD_Table_ID/Record_ID except Attachments/Archive (that's postponed until trx commit)
					PO_Record.deleteRecordCascade(AD_Table_ID, Record_ID, "AD_Table.TableName NOT IN ('AD_Attachment','AD_Archive')", localTrxName);
					// Set referencing Record_ID Null AD_Table_ID/Record_ID
					PO_Record.setRecordNull(AD_Table_ID, Record_ID, localTrxName);
				}
				if (Record_UU != null) {
					PO_Record.deleteModelCascade(p_info.getTableName(), Record_UU, localTrxName);
					PO_Record.deleteRecordCascade(AD_Table_ID, Record_UU, "AD_Table.TableName NOT IN ('AD_Attachment','AD_Archive')", localTrxName);
					PO_Record.setRecordNull(AD_Table_ID, Record_UU, localTrxName);
				}
		
				//	The Delete Statement
				String where = isLogSQLScript() ? get_WhereClause(true, get_ValueAsString(getUUIDColumnName())) : get_WhereClause(true);
				List<Object> optimisticLockingParams = new ArrayList<Object>();
				if (is_UseOptimisticLocking() && m_optimisticLockingColumns != null && m_optimisticLockingColumns.length > 0)
				{
					StringBuilder builder = new StringBuilder(where);
					addOptimisticLockingClause(optimisticLockingParams, builder);
					where = builder.toString();
				}
				StringBuilder sql = new StringBuilder ("DELETE FROM ") //jz why no FROM??
					.append(p_info.getTableName())
					.append(" WHERE ")
					.append(where);
				int no = 0;
				if (isUseTimeoutForUpdate())
					no = optimisticLockingParams.isEmpty() 
						 ? DB.executeUpdateEx(sql.toString(), localTrxName, QUERY_TIME_OUT)
						 : DB.executeUpdateEx(sql.toString(), optimisticLockingParams.toArray(), localTrxName, QUERY_TIME_OUT);
				else
					no = optimisticLockingParams.isEmpty() 
						 ? DB.executeUpdate(sql.toString(), localTrxName)
						 : DB.executeUpdate(sql.toString(), optimisticLockingParams.toArray(), false, localTrxName);
				success = no == 1;
			}
			catch (Exception e)
			{
				String msg = DBException.getDefaultDBExceptionMessage(e);
				log.saveError(msg != null ? msg : e.getLocalizedMessage(), e);
				success = false;
			}
	
			//	Save ID
			m_idOld = get_ID();
			//
			if (!success)
			{
				log.warning("Not deleted");
				if (localTrx != null) 
				{
					localTrx.rollback();
				}
				else if (savepoint != null)
				{
					try {
						trx.rollback(savepoint);
					} catch (SQLException e) {}
					savepoint = null;
				}
			}
			else
			{
				if (success)
				{
					if( p_info.isChangeLog())
					{
						//	Change Log
						MSession session = MSession.get (p_ctx);
						if (session == null)
							log.fine("No Session found");
						else if (m_IDs.length == 1)
						{
							int AD_ChangeLog_ID = 0;
							int size = get_ColumnCount();
							for (int i = 0; i < size; i++)
							{
								Object value = m_oldValues[i];
								if (value != null
									&& p_info.isAllowLogging(i)		//	logging allowed
									&& !p_info.isEncrypted(i)		//	not encrypted
									&& !p_info.isVirtualColumn(i)	//	no virtual column
									&& !"Password".equals(p_info.getColumnName(i))
									)
								{
									// change log on delete
									MChangeLog cLog = session.changeLog (
										m_trxName != null ? m_trxName : localTrxName, AD_ChangeLog_ID,
										AD_Table_ID, p_info.getColumn(i).AD_Column_ID,
										(m_IDs.length == 1 ? Record_ID : 0), Record_UU, getAD_Client_ID(), getAD_Org_ID(), value, null, MChangeLog.EVENTCHANGELOG_Delete);
									if (cLog != null)
										AD_ChangeLog_ID = cLog.getAD_ChangeLog_ID();
								}
							}	//   for all fields
						}
	
						//	Housekeeping
						m_IDs[0] = I_ZERO;
						if (m_trxName == null)
							log.fine("complete");
						else
							if (log.isLoggable(Level.FINE)) log.fine("[" + m_trxName + "] - complete");
						m_attachment = null;
					}
				}
				else
				{
					log.warning("Not deleted");
				}
			}
	
			try
			{
				success = afterDelete (success);
			}
			catch (Exception e)
			{
				log.log(Level.WARNING, "afterDelete", e);
				String msg = DBException.getDefaultDBExceptionMessage(e);
				log.saveError(msg != null ? msg : "Error", e, false);
				success = false;
			//	throw new DBException(e);
			}
	
			// Call ModelValidators TYPE_AFTER_DELETE - teo_sarca [ 1675490 ]
			if (success) {
				errorMsg = ModelValidationEngine.get().fireModelChange(this, ModelValidator.TYPE_AFTER_DELETE);
				if (errorMsg != null) {
					log.saveError("Error", errorMsg);
					success = false;
				}
			}

			if (!success)
			{
				if (localTrx != null) 
				{
					localTrx.rollback();
				}
				else if (savepoint != null)
				{
					try {
						trx.rollback(savepoint);
					} catch (SQLException e) {}
					savepoint = null;
				}
			}
			else
			{
				Trx trxdel = Trx.get(get_TrxName(), false);
				if (trxdel != null) {
					// Schedule the reset cache for after committed the delete
					if (CacheMgt.get().hasCache(p_info.getTableName())) {
						trxdel.addTrxEventListener(new TrxEventListener() {
							@Override
							public void afterRollback(Trx trxdel, boolean success) {
								trxdel.removeTrxEventListener(this);
							}
							@Override
							public void afterCommit(Trx trxdel, boolean success) {
								if (success)
									Adempiere.getThreadPoolExecutor().submit(() -> CacheMgt.get().reset(p_info.getTableName(), Record_ID));
								trxdel.removeTrxEventListener(this);
							}
							@Override
							public void afterClose(Trx trxdel) {
							}
						});
					}
					// trigger the deletion of attachments and archives for after committed the delete
					trxdel.addTrxEventListener(new TrxEventListener() {
						@Override
						public void afterRollback(Trx trxdel, boolean success) {
							trxdel.removeTrxEventListener(this);
						}
						@Override
						public void afterCommit(Trx trxdel, boolean success) {
							if (success) {
								if (m_KeyColumns != null && m_KeyColumns.length == 1 && !getTable().isUUIDKeyTable())
									// Delete Cascade AD_Table_ID/Record_ID on Attachments/Archive
									// after commit because operations on external storage providers don't have rollback
									PO_Record.deleteRecordCascade(AD_Table_ID, Record_ID, "AD_Table.TableName IN ('AD_Attachment','AD_Archive')", null);
								if (Record_UU != null)
									PO_Record.deleteRecordCascade(AD_Table_ID, Record_UU, "AD_Table.TableName IN ('AD_Attachment','AD_Archive')", null);
							}
							trxdel.removeTrxEventListener(this);
						}
						@Override
						public void afterClose(Trx trxdel) {
						}
					});
				}
				if (localTrx != null)
				{
					try {
						localTrx.commit(true);
					} catch (SQLException e) {
						String msg = DBException.getDefaultDBExceptionMessage(e);
						if (msg != null)
							log.saveError(msg, msg, e, false);
						else
							log.saveError("Error", e, false);
						success = false;
					}
				}
			}

			//	Reset
			if (success)
			{
				if (!postDelete()) {
					log.warning("postDelete failed");
				}

				//osgi event handler
				Event event = EventManager.newEvent(IEventTopics.PO_POST_DELETE, this, true);
				EventManager.getInstance().postEvent(event);
	
				m_idOld = 0;
				int size = p_info.getColumnCount();
				m_oldValues = new Object[size];
				m_newValues = new Object[size];
			}
		}
		finally
		{
			if (localTrx != null)
			{
				localTrx.close();
				m_trxName = null;
			}
			else
			{
				if (savepoint != null)
				{
					try {
						trx.releaseSavepoint(savepoint);
					} catch (SQLException e) {
						e.printStackTrace();
					}
				}
				savepoint = null;
				trx = null;
			}
		}
		return success;
	}	//	delete

	/**
	 * Delete Current Record
	 * @param force delete also processed records
	 * @throws AdempiereException if delete fail
	 * @see #delete(boolean)
	 */
	public void deleteEx(boolean force) throws AdempiereException
	{
		if (!delete(force)) {
			String msg = null;
			ValueNamePair err = CLogger.retrieveError();
			if (err != null)
				msg = err.getName();
			if (msg == null || msg.length() == 0)
				msg = "DeleteError";
			Exception ex = CLogger.retrieveException();
			throw new AdempiereException(msg, ex);
		}
	}

	/**
	 * 	Delete Current Record
	 * 	@param force delete also processed records
	 *	@param trxName transaction
	 *	@return true if deleted
	 */
	public boolean delete (boolean force, String trxName)
	{
		set_TrxName(trxName);
		return delete (force);
	}	//	delete

	/**
	 * Delete Current Record
	 * @param force delete also processed records
	 * @param trxName transaction
	 * @throws AdempiereException if delete fail
	 * @see {@link #deleteEx(boolean)}
	 */
	public void deleteEx(boolean force, String trxName) throws AdempiereException
	{
		set_TrxName(trxName);
		deleteEx(force);
	}

	/**
	 * 	Execute before Delete operations.<br/>
	 *  Default implementation is nop, to be implemented in sub-classes that needed it.
	 *	@return true if record can be deleted
	 */
	protected boolean beforeDelete ()
	{
		return true;
	} 	//	beforeDelete

	/**
	 * 	Execute after Delete operations. <br/>
	 *  Default implementation is nop, to be implemented in sub-classes that needed it.
	 * 	@param success true if record deleted
	 *	@return true if delete is a success
	 */
	protected boolean afterDelete (boolean success)
	{
		return success;
	} 	//	afterDelete

	/**
	 * 	Execute after the Delete operation have been committed to database.<br/>
	 *  Default implementation is nop, to be implemented in sub-classes that needed it.
	 *	@return true if post delete is a success
	 */
	protected boolean postDelete()
	{
		return true;
	}

	/**
	 * 	Insert (missing) Translation Records
	 * 	@return false if error (true if no translation or success)
	 */
	private boolean insertTranslations()
	{
		//	Not a translation table
		if (m_IDs.length > 1
			|| m_IDs[0].equals(I_ZERO)
			|| !(m_IDs[0] instanceof Integer || m_IDs[0] instanceof String)
			|| !p_info.isTranslated())
			return true;
		//
		StringBuilder iColumns = new StringBuilder();
		StringBuilder sColumns = new StringBuilder();
		for (int i = 0; i < p_info.getColumnCount(); i++)
		{
			if (p_info.isColumnTranslated(i))
			{
				iColumns.append(p_info.getColumnName(i))
					.append(",");
				sColumns.append("t.")
					.append(p_info.getColumnName(i))
					.append(",");
			}
		}
		if (iColumns.length() == 0)
			return true;

		String tableName = p_info.getTableName();
		String keyColumn = m_KeyColumns[0];

		//check whether db have working generate_uuid function.
		boolean uuidFunction = DB.isGenerateUUIDSupported();

		String trlTableName = tableName + "_Trl";
		MTable trlTable = MTable.get(getCtx(), trlTableName, get_TrxName());
		if (trlTable == null) {
			throw new AdempiereException("Translation table " + trlTableName + " does not exist");
		}
		MColumn uuidColumn = trlTable.getColumn(PO.getUUIDColumnName(trlTableName));

		StringBuilder sql = new StringBuilder ("INSERT INTO ")
			.append(tableName).append("_Trl (AD_Language,")
			.append(keyColumn).append(", ")
			.append(iColumns)
			.append(" IsTranslated,AD_Client_ID,AD_Org_ID,Created,Createdby,Updated,UpdatedBy");
		if (uuidColumn != null && uuidFunction)
			sql.append(",").append(PO.getUUIDColumnName(tableName+"_Trl")).append(" ) ");
		else
			sql.append(" ) ");
		sql.append("SELECT l.AD_Language,t.")
			.append(keyColumn).append(", ")
			.append(sColumns)
			.append(" CASE WHEN l.AD_Language=c.AD_Language THEN 'Y' ELSE 'N' END AS IsTranslated,t.AD_Client_ID,t.AD_Org_ID,t.Created,t.Createdby,t.Updated,t.UpdatedBy");
		if (uuidColumn != null && uuidFunction)
			sql.append(",Generate_UUID() ");
		else
			sql.append(" ");
		sql.append("FROM AD_Language l, ").append(tableName).append(" t, AD_Client c ")
			.append("WHERE t.AD_Client_ID=c.AD_Client_ID AND l.IsActive='Y' AND l.IsSystemLanguage='Y' AND l.IsBaseLanguage='N' AND t.")
			.append(keyColumn).append("=");
		MTable table = MTable.get(getCtx(), tableName);
		if (table.isUUIDKeyTable())
			sql.append(DB.TO_STRING(get_UUID()));
		else
			sql.append(get_ID());
		sql.append(" AND NOT EXISTS (SELECT * FROM ").append(tableName)
			.append("_Trl tt WHERE tt.AD_Language=l.AD_Language AND tt.")
			.append(keyColumn).append("=t.").append(keyColumn).append(")");
		int no = -1;
		try {
			no = DB.executeUpdateEx(sql.toString(), m_trxName);
		} catch (DBException e) {
			String msg;
			if (DBException.isValueTooLarge(e)) {
				msg = Msg.getMsg(getCtx(), "MismatchTrlColumnSize");
			} else {
				msg = "insertTranslations -> " + e.getLocalizedMessage();
			}
			throw new AdempiereException(msg, e);
		}
		if (uuidColumn != null && !uuidFunction) {
			UUIDGenerator.updateUUID(uuidColumn, get_TrxName());
		}
		if (log.isLoggable(Level.FINE)) log.fine("#" + no);
		return no > 0;
	}	//	insertTranslations

	/**
	 * 	Update Translations.
	 * 	@return false if error (true if no translation or success)
	 */
	private boolean updateTranslations()
	{
		//	Not a translation table
		if (m_IDs.length > 1
			|| m_IDs[0].equals(I_ZERO)
			|| !(m_IDs[0] instanceof Integer || m_IDs[0] instanceof String)
			|| !p_info.isTranslated())
			return true;

		String tableName = p_info.getTableName();
		//
		boolean trlColumnChanged = false;
		for (int i = 0; i < p_info.getColumnCount(); i++)
		{
			if (p_info.isColumnTranslated(i)
				&& is_ValueChanged(p_info.getColumnName(i)))
			{
				trlColumnChanged = true;
				break;
			}
		}
		if (!trlColumnChanged)
			return true;
		//
		MClient client = MClient.get(getCtx());
		//
		String keyColumn = m_KeyColumns[0];
		StringBuilder sqlupdate = new StringBuilder("UPDATE ")
			.append(tableName).append("_Trl SET ");

		//
		ArrayList<Object> values = new ArrayList<Object>();
		StringBuilder sqlcols = new StringBuilder();
		for (int i = 0; i < p_info.getColumnCount(); i++)
		{
			String columnName = p_info.getColumnName(i);
			if (p_info.isColumnTranslated(i)
				&& is_ValueChanged(columnName))
			{
				sqlcols.append(columnName).append("=?,");
				values.add(get_Value(columnName));

				// Reset of related translation cache entries
		        String[] availableLanguages = Language.getNames();
		        for (String langName : availableLanguages) {
		    		Language language = Language.getLanguage(langName);
					String key = getTrlCacheKey(columnName, language.getAD_Language());
					CacheMgt.get().reset(TRANSLATION_CACHE_TABLE_NAME, key);
				}
			}
		}
		MTable table = MTable.get(getCtx(), tableName);
		StringBuilder whereid = new StringBuilder(" WHERE ").append(keyColumn).append("=");
		if (table.isUUIDKeyTable())
			whereid.append(DB.TO_STRING(get_UUID()));
		else
			whereid.append(get_ID());
		StringBuilder andClientLang = new StringBuilder(" AND AD_Language=").append(DB.TO_STRING(client.getAD_Language()));
		StringBuilder andNotClientLang = new StringBuilder(" AND AD_Language!=").append(DB.TO_STRING(client.getAD_Language()));
		String baselang = Language.getBaseAD_Language();
		StringBuilder andBaseLang = new StringBuilder(" AND AD_Language=").append(DB.TO_STRING(baselang));
		StringBuilder andNotBaseLang = new StringBuilder(" AND AD_Language!=").append(DB.TO_STRING(baselang));
		int no = -1;

	  try {
		  Object[] params = new Object[values.size()];
		  values.toArray(params);

		if (client.isMultiLingualDocument()) {
			if (client.getAD_Language().equals(baselang)) {
				// tenant language = base language
				// set all translations as untranslated
				StringBuilder sqlexec = new StringBuilder()
					.append(sqlupdate)
					.append("IsTranslated='N'")
					.append(whereid);
				no = DB.executeUpdateEx(sqlexec.toString(), m_trxName);
				if (log.isLoggable(Level.FINE)) log.fine("#" + no);
			} else {
				// tenant language <> base language
				// for Tenants auto update translation for tenant language
				// for System update translation for base language (which in fact must always update zero records as there must not be translations for base)
				StringBuilder sqlexec = new StringBuilder()
					.append(sqlupdate)
					.append(sqlcols)
					.append("IsTranslated='Y'")
					.append(whereid)
					.append(getAD_Client_ID() == 0 ? andBaseLang : andClientLang);
				no = DB.executeUpdateEx(sqlexec.toString(), params, m_trxName);
				if (log.isLoggable(Level.FINE)) log.fine("#" + no);
				if (no >= 0) {
					// set other translations as untranslated
					sqlexec = new StringBuilder()
						.append(sqlupdate)
						.append("IsTranslated='N'")
						.append(whereid)
						.append(getAD_Client_ID() == 0 ? andNotBaseLang : andNotClientLang);
					no = DB.executeUpdateEx(sqlexec.toString(), m_trxName);
					if (log.isLoggable(Level.FINE)) log.fine("#" + no);
				}
			}
			
		} else {
			// auto update all translations
			StringBuilder sqlexec = new StringBuilder()
				.append(sqlupdate)
				.append(sqlcols)
				.append("IsTranslated='Y'")
				.append(whereid);
			no = DB.executeUpdateEx(sqlexec.toString(), params, m_trxName);
			if (log.isLoggable(Level.FINE)) log.fine("#" + no);
		}
	  } catch (DBException e) {
		String msg;
		if (DBException.isValueTooLarge(e)) {
			msg = Msg.getMsg(getCtx(), "MismatchTrlColumnSize");
		} else {
			msg = "updateTranslations -> " + e.getLocalizedMessage();
		}
		throw new AdempiereException(msg, e);
	  }

		return no >= 0;
	}	//	updateTranslations

	/**
	 * 	Delete Translation Records
	 * 	@param trxName transaction
	 * 	@return false if error (true if no translation or success)
	 */
	private boolean deleteTranslations(String trxName)
	{
		//	Not a translation table
		if (m_IDs.length > 1
			|| m_IDs[0].equals(I_ZERO)
			|| !(m_IDs[0] instanceof Integer || m_IDs[0] instanceof String)
			|| !p_info.isTranslated())
			return true;
		//
		String tableName = p_info.getTableName();
		MTable table = MTable.get(getCtx(), tableName);
		String keyColumn = m_KeyColumns[0];
		StringBuilder sql = new StringBuilder ("DELETE FROM ")
			.append(tableName).append("_Trl WHERE ")
			.append(keyColumn).append("=");
		if (table.isUUIDKeyTable())
			sql.append(DB.TO_STRING(get_UUID()));
		else
			sql.append(get_ID());
		int no = DB.executeUpdate(sql.toString(), trxName);
		if (log.isLoggable(Level.FINE)) log.fine("#" + no);
		return no >= 0;
	}	//	deleteTranslations

	/**
	 * 	Insert Accounting Records
	 *	@param acctTableName accounting sub table
	 *	@param acctBaseTable accounting base table to get data from
	 *	@param whereClause optional where clause with alias "p" for acctBaseTable
	 *	@return true if records inserted
	 */
	protected boolean insert_Accounting (String acctTableName,
		String acctBaseTable, String whereClause)
	{
		if (s_acctColumns == null	//	cannot cache C_BP_*_Acct as there are 3
			|| acctTableName.startsWith("C_BP_"))
		{
			s_acctColumns = new ArrayList<String>();
			String sql = "SELECT c.ColumnName "
				+ "FROM AD_Column c INNER JOIN AD_Table t ON (c.AD_Table_ID=t.AD_Table_ID) "
				+ "WHERE t.TableName=? AND c.IsActive='Y' AND c.AD_Reference_ID=25 ORDER BY c.ColumnName";
			PreparedStatement pstmt = null;
			ResultSet rs = null;
			try
			{
				pstmt = DB.prepareStatement (sql, null);
				pstmt.setString (1, acctTableName);
				rs = pstmt.executeQuery ();
				while (rs.next ())
					s_acctColumns.add (rs.getString(1));
			}
			catch (Exception e)
			{
				log.log(Level.SEVERE, acctTableName, e);
			}
			finally {
				DB.close(rs, pstmt);
				rs = null; pstmt = null;
			}
			if (s_acctColumns.size() == 0)
			{
				log.severe ("No Columns for " + acctTableName);
				return false;
			}
		}

		//	Create SQL Statement - INSERT
		StringBuilder sb = new StringBuilder("INSERT INTO ")
			.append(acctTableName)
			.append(" (").append(get_TableName())
			.append("_ID, C_AcctSchema_ID, AD_Client_ID,AD_Org_ID,IsActive, Created,CreatedBy,Updated,UpdatedBy ");
		for (int i = 0; i < s_acctColumns.size(); i++)
			sb.append(",").append(s_acctColumns.get(i));

		//check whether db have working generate_uuid function.
		boolean uuidFunction = DB.isGenerateUUIDSupported();

		MTable acctTable = MTable.get(getCtx(), acctTableName, get_TrxName());
		if (acctTableName == null) {
			throw new AdempiereException("Accounting table " + acctTableName + " does not exist");
		}
		MColumn uuidColumn = acctTable.getColumn(PO.getUUIDColumnName(acctTableName));
		if (uuidColumn != null && uuidFunction)
			sb.append(",").append(PO.getUUIDColumnName(acctTableName));
		//	..	SELECT
		sb.append(") SELECT ").append(get_ID() > MTable.MAX_OFFICIAL_ID && Env.isLogMigrationScript(get_TableName()) 
				 ? "toRecordId("+DB.TO_STRING(get_TableName())+","+DB.TO_STRING(get_UUID())+")" 
				 : get_ID())
			.append(", p.C_AcctSchema_ID, p.AD_Client_ID,0,'Y', getDate(),")
			.append(getUpdatedBy()).append(",getDate(),").append(getUpdatedBy());
		for (int i = 0; i < s_acctColumns.size(); i++)
			sb.append(",p.").append(s_acctColumns.get(i));
		if (uuidColumn != null && uuidFunction)
			sb.append(",generate_uuid()");
		//	.. 	FROM
		sb.append(" FROM ").append(acctBaseTable)
			.append(" p WHERE p.AD_Client_ID=")
			.append(getAD_Client_ID() > MTable.MAX_OFFICIAL_ID && Env.isLogMigrationScript(get_TableName()) 
					? "toRecordId('AD_Client',"+DB.TO_STRING(MClient.get(getAD_Client_ID()).getAD_Client_UU())+")" 
					: getAD_Client_ID());
		if (whereClause != null && whereClause.length() > 0)
			sb.append (" AND ").append(whereClause);
		sb.append(" AND NOT EXISTS (SELECT * FROM ").append(acctTableName)
			.append(" e WHERE e.C_AcctSchema_ID=p.C_AcctSchema_ID AND e.")
			.append(get_TableName()).append("_ID=");
		if (get_ID() > MTable.MAX_OFFICIAL_ID && Env.isLogMigrationScript(get_TableName()))
			sb.append("toRecordId(").append(DB.TO_STRING(get_TableName())).append(",").append(DB.TO_STRING(get_UUID())).append("))");
		else
			sb.append(get_ID()).append(")");
		//
		int no = DB.executeUpdate(sb.toString(), get_TrxName());
		if (no > 0) {
			if (log.isLoggable(Level.FINE)) log.fine("#" + no);
		} else {
			log.warning("#" + no
					+ " - Table=" + acctTableName + " from " + acctBaseTable);
		}

		//fall back to the slow java client update code
		if (uuidColumn != null && !uuidFunction) {
			UUIDGenerator.updateUUID(uuidColumn, get_TrxName());
		}
		return no > 0;
	}	//	insert_Accounting

	/**
	 * 	Delete Accounting records.
	 * 	NOP - done by database constraints
	 *	@param acctTable accounting sub table
	 *	@return true
	 */
	@Deprecated // see IDEMPIERE-2088
	protected boolean delete_Accounting(String acctTable)
	{
		return true;
	}	//	delete_Accounting


	/**
	 * 	Insert id data into Tree
	 * 	@param treeType MTree TREETYPE_*
	 *	@return true if inserted
	 */
	protected boolean insert_Tree (String treeType)
	{
		return insert_Tree (treeType, 0);
	}	//	insert_Tree

	/**
	 * 	Insert id data into Tree
	 * 	@param treeType MTree TREETYPE_*
	 * 	@param C_Element_ID element for accounting element values
	 *	@return true if inserted
	 */
	protected boolean insert_Tree (String treeType, int C_Element_ID)
	{
		String treeTableName = MTree_Base.getNodeTableName(treeType);

		//check whether db have working generate_uuid function.
		boolean uuidFunction = DB.isGenerateUUIDSupported();

		MTable treeTable = MTable.get(getCtx(), treeTableName, get_TrxName());
		if (treeTable == null) {
			throw new AdempiereException("Tree table " + treeTableName + " does not exist");
		}
		MColumn uuidColumn = treeTable.getColumn(PO.getUUIDColumnName(treeTableName));

		StringBuilder sb = new StringBuilder ("INSERT INTO ")
			.append(treeTableName)
			.append(" (AD_Client_ID,AD_Org_ID, IsActive,Created,CreatedBy,Updated,UpdatedBy, "
				+ "AD_Tree_ID, Node_ID, Parent_ID, SeqNo");
		if (uuidColumn != null && uuidFunction)
			sb.append(", ").append(PO.getUUIDColumnName(treeTableName)).append(") ");
		else
			sb.append(") ");
		sb.append("SELECT t.AD_Client_ID, 0, 'Y', getDate(), "+getUpdatedBy()+", getDate(), "+getUpdatedBy()+","
				+ "t.AD_Tree_ID, ")
		  .append(get_ID() > MTable.MAX_OFFICIAL_ID && Env.isLogMigrationScript(get_TableName()) 
				  ? "toRecordId("+DB.TO_STRING(get_TableName())+","+DB.TO_STRING(get_UUID())+")" 
				  : get_ID())
		  .append(", 0, 999");
		if (uuidColumn != null && uuidFunction)
			sb.append(", Generate_UUID() ");
		else
			sb.append(" ");
		sb.append("FROM AD_Tree t "
				+ "WHERE t.AD_Client_ID=")
		  .append(getAD_Client_ID() > MTable.MAX_OFFICIAL_ID && Env.isLogMigrationScript(get_TableName()) 
				  ? "toRecordId('AD_Client',"+DB.TO_STRING(MClient.get(getAD_Client_ID()).getAD_Client_UU())+")" 
				  : getAD_Client_ID())
		  .append(" AND t.IsActive='Y'");
		//	Account Element Value handling
		if (C_Element_ID != 0)
			sb.append(" AND EXISTS (SELECT * FROM C_Element ae WHERE ae.C_Element_ID=")
			  .append(C_Element_ID > MTable.MAX_OFFICIAL_ID && Env.isLogMigrationScript(get_TableName()) 
					  ? "toRecordId('C_Element',"+DB.TO_STRING(new MElement(getCtx(), C_Element_ID, get_TrxName()).getC_Element_UU())+")" 
					  : C_Element_ID)
			  .append(" AND t.AD_Tree_ID=ae.AD_Tree_ID)");
		else	//	std trees
			sb.append(" AND t.IsAllNodes='Y' AND t.TreeType='").append(treeType).append("'");
		if (MTree_Base.TREETYPE_CustomTable.equals(treeType))
			sb.append(" AND t.AD_Table_ID=")
			  .append(get_Table_ID() > MTable.MAX_OFFICIAL_ID && Env.isLogMigrationScript(get_TableName()) 
					  ? "toRecordId('AD_Table',"+DB.TO_STRING(MTable.get(get_Table_ID()).getAD_Table_UU())+")" 
					  : get_Table_ID());
		//	Duplicate Check
		sb.append(" AND NOT EXISTS (SELECT * FROM " + MTree_Base.getNodeTableName(treeType) + " e "
				+ "WHERE e.AD_Tree_ID=t.AD_Tree_ID AND Node_ID=")
		  .append(get_ID() > MTable.MAX_OFFICIAL_ID && Env.isLogMigrationScript(get_TableName()) 
				  ? "toRecordId("+DB.TO_STRING(get_TableName())+","+DB.TO_STRING(get_UUID())+")" 
				  : get_ID()).append(")");
		int no = DB.executeUpdate(sb.toString(), get_TrxName());
		if (no > 0) {
			if (log.isLoggable(Level.FINE)) log.fine("#" + no + " - TreeType=" + treeType);
		} else {
			if (! MTree_Base.TREETYPE_CustomTable.equals(treeType))
				log.warning("#" + no + " - TreeType=" + treeType);
		}

		if (uuidColumn != null && !uuidFunction ) {
			UUIDGenerator.updateUUID(uuidColumn, get_TrxName());
		}
		return no > 0;
	}	//	insert_Tree

	/**
	 * 	Update parent key and seqno based on value if the tree is driven by value 
	 * 	@param treeType MTree TREETYPE_*
	 */
	public void update_Tree (String treeType)
	{
		int idxValueCol = get_ColumnIndex("Value");
		if (idxValueCol < 0)
			return;
		int idxValueIsSummary = get_ColumnIndex("IsSummary");
		if (idxValueIsSummary < 0)
			return;
		String value = get_Value(idxValueCol).toString();
		if (value == null)
			return;

		String tableName = MTree_Base.getNodeTableName(treeType);
		String sourceTableName;
		String whereTree;
		Object[] parameters;
		if (MTree_Base.TREETYPE_CustomTable.equals(treeType)) {
			sourceTableName = this.get_TableName();
			whereTree = "TreeType=? AND AD_Table_ID=?";
			parameters = new Object[]{treeType, this.get_Table_ID()};
		} else {
			sourceTableName = MTree_Base.getSourceTableName(treeType);
			if (MTree_Base.TREETYPE_ElementValue.equals(treeType) && this instanceof I_C_ElementValue) {
				whereTree = "TreeType=? AND AD_Tree_ID=?";
				parameters = new Object[]{treeType, ((I_C_ElementValue)this).getC_Element().getAD_Tree_ID()};
			} else {
				whereTree = "TreeType=?";
				parameters = new Object[]{treeType};
			}
		}
		String updateSeqNo = "UPDATE " + tableName + " SET SeqNo=SeqNo+1 WHERE Parent_ID=? AND SeqNo>=? AND AD_Tree_ID=?";
		String update = "UPDATE " + tableName + " SET SeqNo=?, Parent_ID=? WHERE Node_ID=? AND AD_Tree_ID=?";
		String selMinSeqNo = "SELECT COALESCE(MIN(tn.SeqNo),-1) FROM AD_TreeNode tn JOIN " + sourceTableName + " n ON (tn.Node_ID=n." + sourceTableName + "_ID) WHERE tn.Parent_ID=? AND tn.AD_Tree_ID=? AND n.Value>?";
		String selMaxSeqNo = "SELECT COALESCE(MAX(tn.SeqNo)+1,999) FROM AD_TreeNode tn JOIN " + sourceTableName + " n ON (tn.Node_ID=n." + sourceTableName + "_ID) WHERE tn.Parent_ID=? AND tn.AD_Tree_ID=? AND n.Value<?";

		List<MTree_Base> trees = new Query(getCtx(), MTree_Base.Table_Name, whereTree, get_TrxName())
			.setClient_ID()
			.setOnlyActiveRecords(true)
			.setParameters(parameters)
			.list();

		for (MTree_Base tree : trees) {
			if (tree.isTreeDrivenByValue()) {
				int newParentID = -1;
				if (I_C_ElementValue.Table_Name.equals(sourceTableName)) {
					newParentID = retrieveIdOfElementValue(value, getAD_Client_ID(), ((I_C_ElementValue)this).getC_Element().getC_Element_ID(), get_TrxName());
				} else {
					int linkColId = tree.getParent_Column_ID();
					String linkColName = null;
					int linkID = 0;
					if (linkColId > 0) {
						linkColName = MColumn.getColumnName(Env.getCtx(), linkColId);
						linkID = (Integer)this.get_Value(linkColName);
					}
					newParentID = retrieveIdOfParentValue(value, sourceTableName, linkColName, linkID, getAD_Client_ID(), get_TrxName());
				}
				int seqNo = DB.getSQLValueEx(get_TrxName(), selMinSeqNo, newParentID, tree.getAD_Tree_ID(), value);
				if (seqNo == -1)
					seqNo = DB.getSQLValueEx(get_TrxName(), selMaxSeqNo, newParentID, tree.getAD_Tree_ID(), value);
				DB.executeUpdateEx(updateSeqNo, new Object[] {newParentID, seqNo, tree.getAD_Tree_ID()}, get_TrxName());
				DB.executeUpdateEx(update, new Object[] {seqNo, newParentID, get_ID(), tree.getAD_Tree_ID()}, get_TrxName());
			}
		}
	}	//	update_Tree

	/** 
	 * Get the summary node from C_ElementValue with the corresponding value
	 * @param value
	 * @param clientID
	 * @param elementID
	 * @param trxName
	 * @return C_ElementValue_ID
	 */
	private int retrieveIdOfElementValue(String value, int clientID, int elementID, String trxName)
	{
		String sql = "SELECT C_ElementValue_ID FROM C_ElementValue WHERE IsSummary='Y' AND AD_Client_ID=? AND C_Element_ID=? AND Value=?";
		int pos = value.length()-1;
		while (pos > 0) {
			String testParentValue = value.substring(0, pos);
			int parentID = DB.getSQLValueEx(trxName, sql, clientID, elementID, testParentValue);
			if (parentID > 0)
				return parentID;
			pos--;
		}
		return 0; // rootID
	}

	/** 
	 * Get parent id with the corresponding value
	 * @param value
	 * @param tableName
	 * @param clientID
	 * @param trxName
	 */
	public static int retrieveIdOfParentValue(String value, String tableName, int clientID, String trxName) {
		return retrieveIdOfParentValue(value, tableName, null, 0, clientID, trxName);
	}

	/**
	 * Get parent id with the corresponding value
	 * @param value value to match (partial/starting with or exact match)
	 * @param tableName
	 * @param linkCol optional link column name
	 * @param linkID link id value, ignore if linkCol is null
	 * @param clientID
	 * @param trxName
	 * @return parent id
	 */
	public static int retrieveIdOfParentValue(String value, String tableName, String linkCol, int linkID, int clientID, String trxName)
	{
		String sql = "SELECT " + tableName + "_ID FROM " + tableName + " WHERE IsSummary='Y'";
		if (!Util.isEmpty(linkCol)) {
			sql = sql + " AND " + linkCol + "=" + linkID;
		}
		sql = sql + " AND AD_Client_ID=? AND Value=?";
		int pos = value.length()-1;
		while (pos > 0) {
			String testParentValue = value.substring(0, pos);
			int parentID = DB.getSQLValueEx(trxName, sql, clientID, testParentValue);
			if (parentID > 0)
				return parentID;
			pos--;
		}
		return 0; // rootID
	}

	/**
	 * 	Delete ID Tree Nodes
	 *	@param treeType MTree TREETYPE_*
	 *	@return true if deleted
	 */
	protected boolean delete_Tree (String treeType)
	{
		int id = get_ID();
		if (id == 0)
			id = get_IDOld();
		
		// IDEMPIERE-2453
		StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM ")
			.append(MTree_Base.getNodeTableName(treeType))
			.append(" n JOIN AD_Tree t ON n.AD_Tree_ID=t.AD_Tree_ID")
			.append(" WHERE Parent_ID=? AND t.TreeType=?");
		if (MTree_Base.TREETYPE_CustomTable.equals(treeType))
			countSql.append(" AND t.AD_Table_ID=").append(get_Table_ID());
		int cnt = DB.getSQLValueEx( get_TrxName(), countSql.toString(), id, treeType);
		if (cnt > 0)
			throw new AdempiereException(Msg.getMsg(Env.getCtx(),"NoParentDelete", new Object[] {cnt}));
		
		StringBuilder sb = new StringBuilder ("DELETE FROM ")
			.append(MTree_Base.getNodeTableName(treeType))
			.append(" n WHERE Node_ID=").append(id)
			.append(" AND EXISTS (SELECT * FROM AD_Tree t "
				+ "WHERE t.AD_Tree_ID=n.AD_Tree_ID AND t.TreeType='")
			.append(treeType).append("'");
		if (MTree_Base.TREETYPE_CustomTable.equals(treeType))
			sb.append(" AND t.AD_Table_ID=").append(get_Table_ID());
		sb.append(")");
		int no = DB.executeUpdate(sb.toString(), get_TrxName());
		if (no > 0) {
			if (log.isLoggable(Level.FINE)) log.fine("#" + no + " - TreeType=" + treeType);
		} else {
			if (! MTree_Base.TREETYPE_CustomTable.equals(treeType))
				log.warning("#" + no + " - TreeType=" + treeType);
		}
		return no > 0;
	}	//	delete_Tree

	/**
	 * 	Lock record by update of processing column to Y (not using trx).<br/>
	 *  The method do nothing if PO has no Processing column or existing value of Processing is Y.<br/>
	 *  Note that this is just a logical lock and doesn't acquire real DB lock. To acquire real DB lock,
	 *  use DB.getDatabase().forUpdate instead.
	 * 	@return true if locked
	 */
	public boolean lock()
	{
		int index = get_ProcessingIndex();
		if (index != -1)
		{
			m_newValues[index] = Boolean.TRUE;		//	direct
			String sql = "UPDATE " + p_info.getTableName()
				+ " SET Processing='Y' WHERE (Processing='N' OR Processing IS NULL) AND "
				+ get_WhereClause(true);
			boolean success = false;
			if (isUseTimeoutForUpdate())
				success = DB.executeUpdateEx(sql, null, QUERY_TIME_OUT) == 1;	//	outside trx
			else
				success = DB.executeUpdate(sql, null) == 1;	//	outside trx
			if (success)
				log.fine("success");
			else
				log.log(Level.WARNING, "failed");
			return success;
		}
		return false;
	}	//	lock

	/**
	 * 	Get column index of Processing column 
	 * 	@return column index or -1
	 */
	private int get_ProcessingIndex()
	{
		return p_info.getColumnIndex("Processing");
	}	//	getProcessingIndex

	/**
	 * 	UnLock record by update of processing column to N.<br/>
	 *  The method do nothing if PO has no Processing column.<br/>
	 * 	@param trxName transaction
	 * 	@return true if unlocked (false only if unlock fails)
	 */
	public boolean unlock (String trxName)
	{
		int index = get_ProcessingIndex();
		if (index != -1)
		{
			m_newValues[index] = Boolean.FALSE;		//	direct
			String sql = "UPDATE " + p_info.getTableName()
				+ " SET Processing='N' WHERE " + get_WhereClause(true);
			boolean success = false;
			if (isUseTimeoutForUpdate())
				success = DB.executeUpdateEx(sql, trxName, QUERY_TIME_OUT) == 1;
			else
				success = DB.executeUpdate(sql, trxName) == 1;
			if (success) {
				if (log.isLoggable(Level.FINE)) log.fine("success" + (trxName == null ? "" : "[" + trxName + "]"));
			} else {
				log.log(Level.WARNING, "failed" + (trxName == null ? "" : " [" + trxName + "]"));
			}
			return success;
		}
		return true;
	}	//	unlock

	/**	Optional Transaction		*/
	private String			m_trxName = null;

	/**
	 * 	Set Trx
	 *	@param trxName transaction
	 */
	public void set_TrxName (String trxName)
	{
		if (trxName != null)
		{
			checkImmutable();
		}
		m_trxName = trxName;
	}	//	setTrx

	/**
	 * 	Get Trx
	 *	@return transaction
	 */
	public String get_TrxName()
	{
		return m_trxName;
	}	//	getTrx

	/**
	 * 	Get Attachment.<br/>
	 * 	An attachment is a zip archive with one or more entries.
	 *	@return Attachment or null
	 */
	public MAttachment getAttachment ()
	{
		return getAttachment(false);
	}	//	getAttachment

	/**
	 * 	Get Attachment
	 * 	@param requery true to reload from DB
	 *	@return Attachment or null
	 */
	public MAttachment getAttachment (boolean requery)
	{
		if (m_attachment == null || requery)
			m_attachment = MAttachment.get (getCtx(), p_info.getAD_Table_ID(), get_ID(), get_UUID(), null);
		return m_attachment;
	}	//	getAttachment

	/**
	 * 	Create/return Attachment for PO.<br/>
	 * 	If not exist, create new.
	 *	@return attachment
	 */
	public MAttachment createAttachment()
	{
		getAttachment (false);
		if (m_attachment == null)
			m_attachment = new MAttachment (getCtx(), p_info.getAD_Table_ID(), get_ID(), get_UUID(), null);
		return m_attachment;
	}	//	createAttachment


	/**
	 * 	Do we have a Attachment of type
	 * 	@param extension file extension e.g. .pdf
	 * 	@return true if there is a attachment of type
	 */
	public boolean isAttachment (String extension)
	{
		getAttachment (false);
		if (m_attachment == null)
			return false;
		for (int i = 0; i < m_attachment.getEntryCount(); i++)
		{
			if (m_attachment.getEntryName(i).endsWith(extension))
			{
				if (log.isLoggable(Level.FINE)) log.fine("#" + i + ": " + m_attachment.getEntryName(i));
				return true;
			}
		}
		return false;
	}	//	isAttachment

	/**
	 * 	Get first Attachment Data of type
	 * 	@param extension extension e.g. .pdf
	 *	@return data or null
	 */
	public byte[] getAttachmentData (String extension)
	{
		getAttachment(false);
		if (m_attachment == null)
			return null;
		for (int i = 0; i < m_attachment.getEntryCount(); i++)
		{
			if (m_attachment.getEntryName(i).endsWith(extension))
			{
				if (log.isLoggable(Level.FINE)) log.fine("#" + i + ": " + m_attachment.getEntryName(i));
				return m_attachment.getEntryData(i);
			}
		}
		return null;
	}	//	getAttachmentData

	/**
	 * 	Do we have a PDF Attachment
	 * 	@return true if there is a PDF attachment
	 */
	public boolean isPdfAttachment()
	{
		return isAttachment(".pdf");
	}	//	isPdfAttachment

	/**
	 * 	Get first PDF Attachment Data
	 *	@return data or null
	 */
	public byte[] getPdfAttachment()
	{
		return getAttachmentData(".pdf");
	}	//	getPDFAttachment

	/**
	 *  Dump (with log level finest) where clause and column values 
	 */
	public void dump ()
	{
		if (CLogMgt.isLevelFinest())
		{
			log.finer(get_WhereClause (true));
			for (int i = 0; i < get_ColumnCount (); i++)
				dump (i);
		}
	}   //  dump

	/**
	 *  Dump (with log level finest) column (index:columnName=oldValue (newValue))
	 *  @param index column index
	 */
	public void dump (int index)
	{
		StringBuilder sb = new StringBuilder(" ").append(index);
		if (index < 0 || index >= get_ColumnCount())
		{
			if (log.isLoggable(Level.FINEST)) log.finest(sb.append(": invalid").toString());
			return;
		}
		sb.append(": ").append(get_ColumnName(index))
			.append(" = ").append(m_oldValues[index])
			.append(" (").append(m_newValues[index]).append(")");
		if (log.isLoggable(Level.FINEST)) log.finest(sb.toString());
	}   //  dump

	/**
	 * 	Get All IDs of Table.
	 * 	Used for listing of all records
	 * 	<pre>{@code
	 	int[] IDs = PO.getAllIDs ("AD_PrintFont", null);
		for (int i = 0; i < IDs.length; i++)
		{
			pf = new MPrintFont(Env.getCtx(), IDs[i]);
			System.out.println(IDs[i] + " = " + pf.getFont());
		}
	 *	}</pre>
	 * 	@param TableName table name (key column with _ID)
	 * 	@param WhereClause optional where clause
	 * 	@param trxName transaction
	 * 	@return array of IDs or null
	 */
	public static int[] getAllIDs (String TableName, String WhereClause, String trxName)
	{
		ArrayList<Integer> list = new ArrayList<Integer>();
		StringBuilder sql = new StringBuilder("SELECT ");
		sql.append(TableName).append("_ID FROM ").append(TableName);
		if (WhereClause != null && WhereClause.length() > 0)
			sql.append(" WHERE ").append(WhereClause);
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try
		{
			pstmt = DB.prepareStatement(sql.toString(), trxName);
			rs = pstmt.executeQuery();
			while (rs.next())
				list.add(Integer.valueOf(rs.getInt(1)));
		}
		catch (SQLException e)
		{
			s_log.log(Level.SEVERE, sql.toString(), e);
			return null;
		}
		finally {
			DB.close(rs, pstmt);
			rs = null; pstmt = null;
		}
		//	Convert to array
		int[] retValue = new int[list.size()];
		for (int i = 0; i < retValue.length; i++)
			retValue[i] = ((Integer)list.get(i)).intValue();
		return retValue;
	}	//	getAllIDs

	/**
	 * 	Convert query value.<br/>
	 * 	Convert to upper case and add % at the end.
	 *	@param query in string
	 *	@return converted query value
	 */
	protected static String getFindParameter (String query)
	{
		if (query == null)
			return null;
		if (query.length() == 0 || query.equals("%"))
			return null;
		if (!query.endsWith("%"))
			query += "%";
		return query.toUpperCase();
	}	//	getFindParameter

	/**
	 * 	Load LOB
	 * 	@param value LOB
	 * 	@return loaded LOB object
	 */
	private Object get_LOB (Object value)
	{
		if (log.isLoggable(Level.FINE)) log.fine("Value=" + value);
		if (value == null)
			return null;
		//
		Object retValue = null;

		long length = -99;
		try
		{
			//[ 1643996 ] Chat not working in postgres port
			if (value instanceof String ||
				value instanceof byte[])
				retValue = value;
			else if (value instanceof Clob)		//	returns String
			{
				Clob clob = (Clob)value;
				length = clob.length();
				retValue = clob.getSubString(1, (int)length);
			}
			else if (value instanceof Blob)	//	returns byte[]
			{
				Blob blob = (Blob)value;
				length = blob.length();
				int index = 1;	//	correct
				if (blob.getClass().getName().equals("oracle.jdbc.rowset.OracleSerialBlob"))
					index = 0;	//	Oracle Bug Invalid Arguments
								//	at oracle.jdbc.rowset.OracleSerialBlob.getBytes(OracleSerialBlob.java:130)
				retValue = blob.getBytes(index, (int)length);
			}
			else
				log.log(Level.SEVERE, "Unknown: " + value);
		}
		catch (Exception e)
		{
			log.log(Level.SEVERE, "Length=" + length, e);
		}
		return retValue;
	}	//	getLOB

	/**	LOB Info				*/
	private ArrayList<PO_LOB>	m_lobInfo = null;

	/**
	 * 	Reset LOB info
	 */
	private void lobReset()
	{
		m_lobInfo = null;
	}	//	resetLOB

	/**
	 * 	Prepare LOB save
	 *	@param value LOB value
	 *	@param index column index
	 *	@param displayType display type
	 */
	private void lobAdd (Object value, int index, int displayType)
	{
		if (log.isLoggable(Level.FINEST)) log.finest("Value=" + value);
		PO_LOB lob = new PO_LOB (p_info.getTableName(), get_ColumnName(index),
			get_WhereClause(true), displayType, value);
		if (m_lobInfo == null)
			m_lobInfo = new ArrayList<PO_LOB>();
		m_lobInfo.add(lob);
	}	//	lobAdd

	/**
	 * 	Save LOB
	 * 	@return true if saved ok
	 */
	private boolean lobSave ()
	{
		if (m_lobInfo == null)
			return true;
		boolean retValue = true;
		for (int i = 0; i < m_lobInfo.size(); i++)
		{
			PO_LOB lob = (PO_LOB)m_lobInfo.get(i);
			if (!lob.save(get_WhereClause(true), get_TrxName()))
			{
				retValue = false;
				break;
			}
		}	//	for all LOBs
		lobReset();
		return retValue;
	}	//	saveLOB

	/**
	 * 	Get PO xml representation as string
	 *	@param xml optional string buffer
	 *	@return updated/new string buffer header is only added once
	 */
	public StringBuffer get_xmlString (StringBuffer xml)
	{
		if (xml == null)
			xml = new StringBuffer();
		else
			xml.append(Env.NL);
		//
		try
		{
			StringWriter writer = new StringWriter();
			StreamResult result = new StreamResult(writer);
			DOMSource source = new DOMSource(get_xmlDocument(xml.length()!=0));
			TransformerFactory tFactory = TransformerFactory.newInstance();
			Transformer transformer = tFactory.newTransformer();
			transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes");
			transformer.transform (source, result);
			StringBuffer newXML = writer.getBuffer();
			//
			if (xml.length() != 0)
			{	//	//	<?xml version="1.0" encoding="UTF-8"?>
				int tagIndex = newXML.indexOf("?>");
				if (tagIndex != -1)
					xml.append(newXML.substring(tagIndex+2));
				else
					xml.append(newXML);
			}
			else
				xml.append(newXML);
		}
		catch (Exception e)
		{
			log.log(Level.SEVERE, "", e);
		}
		return xml;
	}	//	get_xmlString

	/** Table ID Attribute		*/
	protected final static String 	XML_ATTRIBUTE_AD_Table_ID = "AD_Table_ID";
	/** Record ID Attribute		*/
	protected final static String 	XML_ATTRIBUTE_Record_ID = "Record_ID";

	/**
	 * 	Get XML Document representation
	 * 	@param noComment do not add comment
	 * 	@return XML document
	 */
	public Document get_xmlDocument(boolean noComment)
	{
		Document document = null;
		try
		{
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			DocumentBuilder builder = factory.newDocumentBuilder();
			document = builder.newDocument();
			if (!noComment)
				document.appendChild(document.createComment(Adempiere.getSummaryAscii()));
		}
		catch (Exception e)
		{
			log.log(Level.SEVERE, "", e);
		}
		//	Root
		Element root = document.createElement(get_TableName());
		root.setAttribute(XML_ATTRIBUTE_AD_Table_ID, String.valueOf(get_Table_ID()));
		root.setAttribute(XML_ATTRIBUTE_Record_ID, String.valueOf(get_ID()));
		document.appendChild(root);
		//	Columns
		int size = get_ColumnCount();
		for (int i = 0; i < size; i++)
		{
			if (p_info.isVirtualColumn(i))
				continue;

			Element col = document.createElement(p_info.getColumnName(i));
			//
			Object value = get_Value(i);
			//	Display Type
			int dt = p_info.getColumnDisplayType(i);
			//  Based on class of definition, not class of value
			Class<?> c = p_info.getColumnClass(i);
			if (value == null || value.equals (Null.NULL))
				;
			else if (c == Object.class)
			{
				col.setAttributeNS(XMLConstants.XML_NS_URI, "space", "preserve");
				col.appendChild(document.createCDATASection(value.toString()));
			}
			else if (value instanceof Integer || value instanceof BigDecimal)
				col.appendChild(document.createTextNode(value.toString()));
			else if (c == Boolean.class)
			{
				boolean bValue = false;
				if (value instanceof Boolean)
					bValue = ((Boolean)value).booleanValue();
				else
					bValue = "Y".equals(value);
				col.appendChild(document.createTextNode(bValue ? "Y" : "N"));
			}
			else if (value instanceof Timestamp)
				col.appendChild(document.createTextNode(value.toString()));
			else if (c == String.class)
			{
				col.setAttributeNS(XMLConstants.XML_NS_URI, "space", "preserve");
				col.appendChild(document.createCDATASection((String)value));
			}
			else if (DisplayType.isLOB(dt))
			{
				col.setAttributeNS(XMLConstants.XML_NS_URI, "space", "preserve");
				col.appendChild(document.createCDATASection(value.toString()));
			}
			else
			{
				col.setAttributeNS(XMLConstants.XML_NS_URI, "space", "preserve");
				col.appendChild(document.createCDATASection(value.toString()));
			}
			//
			root.appendChild(col);
		}
		//	Custom Columns
		if (m_custom != null)
		{
			Iterator<String> it = m_custom.keySet().iterator();
			while (it.hasNext())
			{
				String columnName = (String)it.next();
				String value = (String)m_custom.get(columnName);
				//
				Element col = document.createElement(columnName);
				if (value != null)
					col.appendChild(document.createTextNode(value));
				root.appendChild(col);
			}
			m_custom = null;
		}
		return document;
	}	//	getDocument

	/** Doc - To be used on ModelValidator to get the corresponding Doc from the PO */
	private Doc m_doc;

	/**
	 * Set the accounting document associated to the PO - for use in POST ModelValidator
	 * @param doc Document
	 */
	public void setDoc(Doc doc) {
		m_doc = doc;		
	}

	/**
	 * Set replication flag
	 * @param isFromReplication
	 */
	public void setReplication(boolean isFromReplication)
	{
		m_isReplication = isFromReplication;
	}

	/**
	 * Is for replication
	 * @return true if it is for replication
	 */
	public boolean isReplication()
	{
		return m_isReplication;
	}

	/**
	 * Get the accounting document associated to the PO - for use in POST ModelValidator
	 * @return Doc Document
	 */
	public Doc getDoc() {
		return m_doc;
	}

	/**
	 *  Set given trxName to an array of POs
	 */
	public static void set_TrxName(PO[] lines, String trxName) {
		for (PO line : lines)
			line.set_TrxName(trxName);
	}

	/**
	 * Get Value as int
	 * @param columnName
	 * @return int value
	 */
	public int get_ValueAsInt (String columnName)
	{
		int idx = get_ColumnIndex(columnName);
		if (idx < 0)
		{
			return 0;
		}
		return get_ValueAsInt(idx);
	}

	/**
	 * Get value as boolean
	 * @param columnName
	 * @return boolean value
	 */
	public boolean get_ValueAsBoolean(String columnName)
	{
		Object oo = get_Value(columnName);
		if (oo != null)
		{
			 if (oo instanceof Boolean)
				 return ((Boolean)oo).booleanValue();
			return "Y".equals(oo);
		}
		return false;
	}

	 /**
	  * Get UUID column name
	  * @return uuid column name
	  */
	public String getUUIDColumnName() {
		return PO.getUUIDColumnName(get_TableName());
	}

	/**
	 * Get UUID column name
	 * @param tableName
	 * @return uuid column name
	 */
	public static String getUUIDColumnName(String tableName) {

		// easy case, just add suffix when the table name is shorter or equal than 27 chars
		String columnName = tableName + "_UU";
		if (columnName.length() <= 30) /* Old MAX_OBJECT_NAME_LENGTH */
			return columnName;

		// verify if oldColumnName exists
		int i = columnName.length() - 30;
		String oldColumnName = tableName.substring(0, tableName.length() - i) + "_UU";
		MTable table = MTable.get(null, tableName);
		if (table != null && table.columnExists(oldColumnName))
			return oldColumnName;

		if (columnName.length() > AdempiereDatabase.MAX_OBJECT_NAME_LENGTH) {
			i = columnName.length() - AdempiereDatabase.MAX_OBJECT_NAME_LENGTH;
			columnName = tableName.substring(0, tableName.length() - i) + "_UU";
		}
		return columnName;
	}
	
	@Override
	@Deprecated
	protected Object clone() throws CloneNotSupportedException {
		PO clone = (PO) super.clone();
		clone.m_trxName = null;
		if (m_custom != null)
		{
			clone.m_custom = new HashMap<String, String>();
			clone.m_custom.putAll(m_custom);
		}
		if (m_newValues != null)
		{
			clone.m_newValues = new Object[m_newValues.length];
			for(int i = 0; i < m_newValues.length; i++)
			{
				clone.m_newValues[i] = m_newValues[i];
			}
		}
		if (m_oldValues != null)
		{
			clone.m_oldValues = new Object[m_oldValues.length];
			for(int i = 0; i < m_oldValues.length; i++)
			{
				clone.m_oldValues[i] = m_oldValues[i];
			}
		}
		if (m_IDs != null)
		{
			clone.m_IDs = new Object[m_IDs.length];
			for(int i = 0; i < m_IDs.length; i++)
			{
				clone.m_IDs[i] = m_IDs[i];
			}
		}
		clone.p_ctx = Env.getCtx();
		clone.m_doc = null;
		clone.m_lobInfo = null;
		clone.m_attachment = null;
		clone.m_isReplication = false;
		return clone;
	}

	/**
	 * Read object from ois (for serialization)
	 * @param ois
	 * @throws ClassNotFoundException
	 * @throws IOException
	 */
	private void readObject(ObjectInputStream ois)
			throws ClassNotFoundException, IOException {
	    // default deserialization
	    ois.defaultReadObject();
	    log = CLogger.getCLogger(getClass());
	    p_ctx = Env.getCtx();
	    p_info = initPO(p_ctx);
	}
	
	/**
	 * set attribute value
	 * @param attributeName
	 * @param value
	 */
	public void set_Attribute(String attributeName, Object value) {
		checkImmutable();
		
		if (m_attributes == null)
			m_attributes = new HashMap<String, Object>();
		m_attributes.put(attributeName, value);
	}
	
	/**
	 * Get attribute value
	 * @param attributeName
	 * @return attribute value
	 */
	public Object get_Attribute(String attributeName) {
		if (m_attributes != null)
			return m_attributes.get(attributeName);
		return null;
	}
	
	/**
	 * Get attribute map
	 * @return map of attributes
	 */
	public HashMap<String,Object> get_Attributes() {
		return m_attributes;
	}

	/**
	 * Mark PO as immutable.<br/>
	 * For PO that have been marked as immutable, {@link #checkImmutable()} will throw exception.
	 */
	protected void makeImmutable() {
		if (is_Immutable()) 
			return;
		
		m_isImmutable = true;
		m_trxName = null;
	}
	
	/**
	 * Is PO immutable
	 * @return true if PO is immutable, false otherwise
	 */
	public boolean is_Immutable() {
		return m_isImmutable;
	}
	
	/**
	 * Check if last error (if exists) is caused by unique constraint/index.
	 */
	private void validateUniqueIndex()
	{
		ValueNamePair ppE = CLogger.retrieveError();
		if (ppE != null)
		{
			String msg = ppE.getValue();
			String info = ppE.getName();
			if ("DBExecuteError".equals(msg))
				info = "DBExecuteError:" + info;
			//	Unique Constraint
			Exception e = CLogger.peekException();
			if (DBException.isUniqueContraintError(e))
			{
				boolean found = false;
				String dbIndexName = DB.getDatabase().getNameOfUniqueConstraintError(e);
				if (log.isLoggable(Level.FINE)) log.fine("dbIndexName=" + dbIndexName);
				MTableIndex index = new Query(getCtx(), MTableIndex.Table_Name, "AD_Table_ID=? AND UPPER(Name)=UPPER(?)", null)
						.setParameters(get_Table_ID(), dbIndexName)
						.setOnlyActiveRecords(true)
						.first();
				if (index != null && index.getAD_Message_ID() > 0)
				{
					MMessage message = MMessage.get(getCtx(), index.getAD_Message_ID());
					log.saveError("SaveError", Msg.getMsg(getCtx(), message.getValue()));
					found = true;
				}
				if (!found)
					log.saveError(msg, info);
			}
			else
				log.saveError(msg, info);
		}
	}

	/**
	 * Throw exception if session context is invalid
	 */
	private void checkValidContext() {
		if (getCtx().isEmpty() && getCtx().getProperty(Env.AD_CLIENT_ID) == null)
			throw new AdempiereException("Context lost");
	}

	/**
	 * To force a cross tenant safe read/write the client program must write code like this:
	 * <pre>
		try {
			PO.setCrossTenantSafe();
			// write here the Query.list or PO.saveEx that is cross tenant safe
		} finally {
			PO.clearCrossTenantSafe();
		}
	   </pre>
	 */
	private static ThreadLocal<Boolean> isSafeCrossTenant = new ThreadLocal<Boolean>() {
		@Override protected Boolean initialValue() {
			return Boolean.FALSE;
		};
	};
	
	/**
	 * Turn on cross tenant safe thread local flag
	 */
	public static void setCrossTenantSafe() {
		isSafeCrossTenant.set(Boolean.TRUE);
	}
	
	/**
	 * Clear cross tenant safe thread local flag
	 */
	public static void clearCrossTenantSafe() {
		isSafeCrossTenant.set(Boolean.FALSE);
	}

	/**
	 * Throw exception if this is a cross tenant operation and cross tenant safe flag is not turn on.
	 * @param writing
	 */
	private void checkCrossTenant(boolean writing) {
		if (isSafeCrossTenant.get())
			return;
		int envClientID = Env.getAD_Client_ID(getCtx());
		// processes running from system client can read/write always
		if (envClientID > 0) {
			int poClientID = getAD_Client_ID();
			if (poClientID != envClientID &&
					(poClientID != 0 || writing)) {
				log.warning("Table="+get_TableName()
					+" Record_ID="+get_ID()
					+" Env.AD_Client_ID="+envClientID
					+" PO.AD_Client_ID="+poClientID
					+" writing="+writing
					+" Session="+Env.getContext(getCtx(), Env.AD_SESSION_ID));
				throw new CrossTenantException(writing, get_TableName(), get_ID());
			}
		}
	}
	
	/**
	 * Validates foreign key constraints for the current record.<br/>
	 * To be called programmatically before saving in programs that can receive arbitrary values in IDs.<br/>
	 * This is an expensive operation in terms of database, use it wisely.
	 * <p>
	 * This method ensures that:
	 * <ul>
	 *   <li>Foreign key values exist in the referenced table.</li>
	 *   <li>System-level records are only used where allowed.</li>
	 *   <li>Cross-tenant references are prevented.</li>
	 * </ul>
	 * If any validation fails, an appropriate exception is thrown.
	 *
	 * @throws AdempiereException   If the foreign key value does not exist in the referenced table.
	 * @throws CrossTenantException If a cross-tenant reference is detected.
	 */
	public void validForeignKeysEx() {
		List<ValueNamePair> fks = getForeignColumnIdxs();
		if (fks == null) {
			return;
		}
		for (ValueNamePair vnp : fks) {
			String fkcol = vnp.getID();
			String fktab = vnp.getName();
			int index = get_ColumnIndex(fkcol); 
			if (is_new() || is_ValueChanged(index)) {
				Object fkval = null;
				if (fkcol.endsWith("_UU")) {
					fkval = get_ValueAsString(index);
				} else {
					fkval = Integer.valueOf(get_ValueAsInt(index));
				}
				if (fkval != null
					&& (   (fkval instanceof Integer && ((Integer)fkval).intValue() > 0)
						|| (fkval instanceof String && ((String)fkval).length() > 0) )) {
					MTable ft = MTable.get(getCtx(), fktab);
					boolean systemAccess = false;
					String accessLevel = ft.getAccessLevel();
					if (   MTable.ACCESSLEVEL_All.equals(accessLevel)
						|| MTable.ACCESSLEVEL_SystemOnly.equals(accessLevel)
						|| MTable.ACCESSLEVEL_SystemPlusClient.equals(accessLevel)) {
						systemAccess = true;
					}
					StringBuilder sql = new StringBuilder("SELECT AD_Client_ID FROM ")
							.append(fktab)
							.append(" WHERE ")
							.append(ft.getKeyColumns()[0])
							.append("=?");
					int pocid = DB.getSQLValue(get_TrxName(), sql.toString(), fkval);
					if (pocid < 0) {
						throw new AdempiereException("Foreign ID " + fkval + " not found in " + fkcol);
					}
					if (pocid == 0 && !systemAccess) {
						throw new CrossTenantException(fkval, fkcol);
					}
					int curcid = Env.getAD_Client_ID(getCtx());
					if (pocid > 0 && pocid != curcid) {
						throw new CrossTenantException(fkval, fkcol);
					}
				}
			}
		}
	}

	/**
	 * Validates foreign key constraints for the current record.
	 * <p>
	 * This method calls {@link #validForeignKeysEx()} and returns a boolean result instead of throwing exceptions.
	 * It ensures that:
	 * <ul>
	 *   <li>Foreign key values exist in the referenced table.</li>
	 *   <li>System-level records are only used where allowed.</li>
	 *   <li>Cross-tenant references are prevented.</li>
	 * </ul>
	 * If validation fails, the method returns {@code false} instead of throwing an exception.
	 * 
 	 * <pre>
	 * TODO: there is huge room for performance improvement, for example:
	 * - caching the valid values found on foreign tables
	 * - caching the column ID of the foreign column
	 * - caching the systemAccess
	 * </pre>
	 *
	 * @return {@code true} if all foreign key constraints are valid, {@code false} otherwise.
	 */
	public boolean validForeignKeys() {
		try {
			validForeignKeysEx();
		} catch (Exception e) {
			log.saveError("Error", e.getMessage());
			return false;
		}
		return true;
	}

	/**
	 * Verify Foreign key based on AD_Table_ID+Record_ID for cross tenant.<br/>
	 * Throw exception if Record_ID reference is cross tenant and the cross tenant safe flag is not turn on.
	 * @return true if all the foreign keys are valid
	 */
	private void checkRecordIDCrossTenant() {
		if (isSafeCrossTenant.get())
			return;
		
		//ad_table_id+record_id validation will fail for ad_pinstance due to ad_pinstance is 
		//being saved and updated outside of server process transaction.
		if (I_AD_PInstance.Table_Name.equals(p_info.getTableName()))
			return;
		
		int idxRecordId = p_info.getColumnIndex("Record_ID");
		if (idxRecordId < 0)
			return;
		int idxTableId = p_info.getColumnIndex("AD_Table_ID");
		if (idxTableId < 0)
			return;
		if ( ! (is_new() || is_ValueChanged(idxTableId) || is_ValueChanged(idxRecordId)))
			return;
		int recordId = get_ValueAsInt(idxRecordId);
		if (recordId <= 0)
			return;
		int tableId = get_ValueAsInt(idxTableId);
		if (tableId <= 0)
			return;
		MTable ft = MTable.get(getCtx(), tableId);
		if (ft.getKeyColumns().length > 1)
			return; // multi-key-table
		boolean systemAccess = false;
		String accessLevel = ft.getAccessLevel();
		if (   MTable.ACCESSLEVEL_All.equals(accessLevel)
			|| MTable.ACCESSLEVEL_SystemOnly.equals(accessLevel)
			|| MTable.ACCESSLEVEL_SystemPlusClient.equals(accessLevel)) {
			systemAccess = true;
		}
		StringBuilder sql = new StringBuilder("SELECT AD_Client_ID FROM ")
				.append(ft.getTableName())
				.append(" WHERE ")
				.append(ft.getKeyColumns()[0])
				.append("=?");
		int pocid = DB.getSQLValue(get_TrxName(), sql.toString(), recordId);
		if (pocid < 0)
			throw new AdempiereException("Foreign ID " + recordId + " not found in " + ft.getTableName());
		if (pocid == 0 && !systemAccess)
			throw new CrossTenantException(ft.getTableName(), recordId);
		int curcid = getAD_Client_ID();
		if (pocid > 0 && pocid != curcid)
			throw new CrossTenantException(ft.getTableName(), recordId);
	}

	/**
	 * Verify Foreign key based on AD_Table_ID+Record_UU for cross tenant.
	 * Throw exception if Record_UU reference is cross tenant and the cross tenant safe flag is not turn on.
	 * @return true if all the foreign keys are valid
	 */
	private void checkRecordUUCrossTenant() {
		if (isSafeCrossTenant.get())
			return;

		//ad_table_id+record_uu validation will fail for ad_pinstance due to ad_pinstance is 
		//being saved and updated outside of server process transaction.
		if (I_AD_PInstance.Table_Name.equals(p_info.getTableName()))
			return;

		int idxRecordUU = p_info.getColumnIndex("Record_UU");
		if (idxRecordUU < 0)
			return;
		int idxTableId = p_info.getColumnIndex("AD_Table_ID");
		if (idxTableId < 0)
			return;
		if ( ! (is_new() || is_ValueChanged(idxTableId) || is_ValueChanged(idxRecordUU)))
			return;
		int tableId = get_ValueAsInt(idxTableId);
		if (tableId <= 0)
			return;
		String recordUU = get_ValueAsString(idxRecordUU);
		if (Util.isEmpty(recordUU))
			return;
		MTable ft = MTable.get(getCtx(), tableId);
		if (!ft.hasUUIDKey())
			return; // no UUID key in table
		boolean systemAccess = false;
		String accessLevel = ft.getAccessLevel();
		if (   MTable.ACCESSLEVEL_All.equals(accessLevel)
			|| MTable.ACCESSLEVEL_SystemOnly.equals(accessLevel)
			|| MTable.ACCESSLEVEL_SystemPlusClient.equals(accessLevel)) {
			systemAccess = true;
		}
		StringBuilder sql = new StringBuilder("SELECT AD_Client_ID FROM ")
				.append(ft.getTableName())
				.append(" WHERE ")
				.append(PO.getUUIDColumnName(ft.getTableName()))
				.append("=?");
		int pocid = DB.getSQLValue(get_TrxName(), sql.toString(), recordUU);
		if (pocid < 0)
			throw new AdempiereException("Foreign UUID " + recordUU + " not found in " + ft.getTableName());
		if (pocid == 0 && !systemAccess)
			throw new CrossTenantException(ft.getTableName(), recordUU);
		int curcid = getAD_Client_ID();
		if (pocid > 0 && pocid != curcid)
			throw new CrossTenantException(ft.getTableName(), recordUU);
	}

	/**
	 * Get foreign key columns
	 * @return list of foreign key columns (Column Name, Reference Table Name)
	 */
	private List<ValueNamePair> getForeignColumnIdxs() {
		List<ValueNamePair> retValue;
		if (fks_cache.containsKey(get_Table_ID())) {
			retValue = fks_cache.get(get_Table_ID());
			return retValue;
		}
		retValue = new ArrayList<ValueNamePair>();
		int size = get_ColumnCount();
		for (int i = 0; i < size; i++) {
			int dt = p_info.getColumnDisplayType(i);
			if (   (dt != DisplayType.ID   && DisplayType.isID(dt)  )
				|| (dt != DisplayType.UUID && DisplayType.isUUID(dt)) ) {
				MColumn col = MColumn.get(p_info.getColumn(i).AD_Column_ID);
				if ("AD_Client_ID".equals(col.getColumnName())) {
					// ad_client_id is verified with checkValidClient
					continue;
				}
				String refTable = col.getReferenceTableName();
				retValue.add(new ValueNamePair(col.getColumnName(), refTable));
			}
		}
		if (retValue.size() == 0) {
			retValue = null;
		}
		fks_cache.put(get_Table_ID(), retValue);
		return retValue;
	}

	/**
	 * Verify if a column exists
	 * @param columnName
	 * @param throwException true to throw exception when the column doesn't exist
	 * @return true if column exists
	 */
	public boolean columnExists(String columnName, boolean throwException) {
		int idx = get_ColumnIndex(columnName);
		if (idx < 0 && throwException)
			throw new AdempiereException("Column " + get_TableName() +"." + columnName + " not found");
		return (idx >= 0);
	}

	/**
	 * Verify if a column exists
	 * @param columnName
	 * @return true if column exists
	 */
	public boolean columnExists(String columnName) {
		return columnExists(columnName, false);
	}

	/**
	 * @param attributeName
	 * @return
	 */
	public boolean get_TableAttributeAsBoolean(String attributeName)
	{
		Object value = get_TableAttribute(attributeName);
		if (value != null)
		{
			 if (value instanceof Boolean)
				 return ((Boolean)value).booleanValue();
			return "Y".equals(value);
		}
		return false;
	} // get_TableAttributeAsBoolean

	/**
	 * @param attributeName
	 * @return
	 */
	public int get_TableAttributeAsInt(String attributeName)
	{
		Object value = get_TableAttribute(attributeName);
		if (value == null)
			return 0;
		if (value instanceof Integer)
			return ((Integer)value).intValue();
		try
		{
			return Integer.parseInt(value.toString());
		}
		catch (NumberFormatException ex)
		{
			log.warning("Attribute " + attributeName + " - " + ex.getMessage());
			return 0;
		}
	} // get_TableAttributeAsInt

	/**
	 * Return attribute value for table and record.
	 * Load All attribute first time, then only query attribute that are not in map.
	 * TODO can write different method to get directly cast value like get_TableAttributeAsString, get_TableAttributeAsDate etc..
	 * 
	 * @param  attributeName
	 * @return
	 */
	public Object get_TableAttribute(String attributeName)
	{
		if (m_tableAttributeMap.isEmpty() || !m_tableAttributeMap.containsKey(attributeName))
		{
			PreparedStatement pstmt = null;
			ResultSet rs = null;
			try
			{
				String where = m_tableAttributeMap.isEmpty() ? "" : " AND a.Name = ? ";
				// 4 - String, 5 - data, 6 - number, 7 - attribute value
				pstmt = DB.prepareStatement(TABLE_ATTRIBUTE_VALUE_SQL + where, null);
				pstmt.setInt(1, get_Table_ID());
				pstmt.setInt(2, get_ID());
				if (!m_tableAttributeMap.isEmpty())
					pstmt.setString(3, attributeName);

				rs = pstmt.executeQuery();
				while (rs.next())
				{
					Object value = null;
					String attName = rs.getString(1);
					String attType = rs.getString(2);
					int reference_ID = rs.getInt(3);

					if (MAttribute.ATTRIBUTEVALUETYPE_Number.equalsIgnoreCase(attType))
					{
						value = rs.getInt(6);
					}
					else if (MAttribute.ATTRIBUTEVALUETYPE_Date.equalsIgnoreCase(attType))
					{
						value = rs.getDate(5) != null ? new Timestamp(rs.getDate(5).getTime()) : null;
					}
					else if (MAttribute.ATTRIBUTEVALUETYPE_List.equalsIgnoreCase(attType))
					{
						value = rs.getInt(7);
					}
					else if (MAttribute.ATTRIBUTEVALUETYPE_StringMax40.equalsIgnoreCase(attType))
					{
						value = rs.getString(4);
					}
					else if (MAttribute.ATTRIBUTEVALUETYPE_Reference.equalsIgnoreCase(attType))
					{
						if (reference_ID == DisplayType.YesNo)
						{
							value = Util.isEmpty(rs.getString(4)) ? null: rs.getString(4).equalsIgnoreCase("Y");
						}
						else if (DisplayType.isText(reference_ID))
						{
							value = rs.getString(4);
						}
						else if (DisplayType.isDate(reference_ID))
						{
							value = rs.getDate(5) != null ? new Timestamp(rs.getDate(5).getTime()) : null;
						}
						else if (DisplayType.isNumeric(reference_ID) || DisplayType.isID(reference_ID))
						{
							value = rs.getInt(6);
						}
						else
						{
							value = rs.getString(4);
						}
					}
					else
					{
						value = rs.getString(4);
					}

					if (value != null)
						m_tableAttributeMap.put(attName, value);
				}
			}
			catch (Exception e)
			{
				CLogger.get().log(Level.SEVERE, "Failed: Get Attribute = " + attributeName, e);
				return null;
			}
			finally
			{
				DB.close(rs, pstmt);
				rs = null;
				pstmt = null;
			}
		}

		if (m_tableAttributeMap.containsKey(attributeName))
			return m_tableAttributeMap.get(attributeName);

		return MTableAttribute.getAttributeDefaultValue(attributeName, get_Table_ID());
	} // get_TableAttribute

	/**
	 * Retrieves the table attributes associated with the current record.
	 * 
	 * @return a list of {@link PO} objects representing table attributes
	 *         filtered by the table ID and record ID.
	 */
	public List<PO> get_TableAttributes()
	{
		return new Query(Env.getCtx(), MTableAttribute.Table_Name, "AD_Table_ID=? AND Record_ID=? ", null).setParameters(get_Table_ID(), get_ID()).list();
	}

}   //  PO
