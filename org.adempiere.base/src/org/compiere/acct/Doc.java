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
package org.compiere.acct;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Properties;
import java.util.logging.Level;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.exceptions.DBException;
import org.compiere.model.I_C_AllocationHdr;
import org.compiere.model.I_C_Cash;
import org.compiere.model.I_C_ProjectIssue;
import org.compiere.model.I_M_MatchInv;
import org.compiere.model.I_M_MatchPO;
import org.compiere.model.MAccount;
import org.compiere.model.MAcctSchema;
import org.compiere.model.MConversionRate;
import org.compiere.model.MDocType;
import org.compiere.model.MInOut;
import org.compiere.model.MInvoice;
import org.compiere.model.MMatchInv;
import org.compiere.model.MMatchPO;
import org.compiere.model.MNote;
import org.compiere.model.MPeriod;
import org.compiere.model.MRefList;
import org.compiere.model.ModelValidationEngine;
import org.compiere.model.ModelValidator;
import org.compiere.model.PO;
import org.compiere.process.DocumentEngine;
import org.compiere.util.AdempiereUserError;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.DisplayType;
import org.compiere.util.Env;
import org.compiere.util.Msg;
import org.compiere.util.Trx;
import org.compiere.util.Util;

/**
 *  Abstract base class for posting of accounting document.
 *
 *  <pre>
 *  Table               Base Document Types (C_DocType.DocBaseType and AD_Reference_ID=183)
 *      Class           AD_Table_ID
 *  ------------------  ------------------------------
 *  C_Invoice:          ARI, ARC, ARF, API, APC
 *      Doc_Invoice     318 - has C_DocType_ID
 *
 *  C_Payment:          ARP, APP
 *      Doc_Payment     335 - has C_DocType_ID
 *
 *  C_Order:            SOO, POO,  POR (Requisition)
 *      Doc_Order       259 - has C_DocType_ID
 *
 *  M_InOut:            MMS, MMR
 *      Doc_InOut       319 - DocType derived
 *
 *  M_Inventory:        MMI
 *      Doc_Inventory   321 - DocType fixed
 *
 *  M_Movement:         MMM
 *      Doc_Movement    323 - DocType fixed
 *
 *  M_Production:       MMP
 *      Doc_Production  325 - DocType fixed
 *
 * M_Production:        MMO
 *      Doc_CostCollector  330 - DocType fixed
 *
 *  C_BankStatement:    CMB
 *      Doc_Bank        392 - DocType fixed
 *
 *  C_Cash:             CMC
 *      Doc_Cash        407 - DocType fixed
 *
 *  C_Allocation:       CMA
 *      Doc_Allocation  390 - DocType fixed
 *
 *  GL_Journal:         GLJ
 *      Doc_GLJournal   224 = has C_DocType_ID
 *
 *  Matching Invoice    MXI
 *      M_MatchInv      472 - DocType fixed
 *
 *  Matching PO         MXP
 *      M_MatchPO       473 - DocType fixed
 *
 *	Project Issue		PJI
 *		C_ProjectIssue	623 - DocType fixed
 *
 *  </pre>
 *  @author Jorg Janke
 *  @author victor.perez@e-evolution.com, e-Evolution http://www.e-evolution.com
 * 				<li>FR [ 2520591 ] Support multiples calendar for Org
 *				@see https://sourceforge.net/p/adempiere/feature-requests/631/
 *  @version  $Id: Doc.java,v 1.6 2006/07/30 00:53:33 jjanke Exp $
 */
public abstract class Doc
{
	/**************************************************************************
	 * 	 Document Types
	 *  --------------
	 *  C_DocType.DocBaseType and AD_Reference_ID=183
	 *  C_Invoice:          ARI, ARC, ARF, API, APC
	 *  C_Payment:          ARP, APP
	 *  C_Order:            SOO, POO
	 *  M_Transaction:      MMI, MMM, MMS, MMR
	 *  C_BankStatement:    CMB
	 *  C_Cash:             CMC
	 *  C_Allocation:       CMA
	 *  GL_Journal:         GLJ
	 *  C_ProjectIssue		PJI
	 *  M_Requisition		POR
	 **************************************************************************/

	public static final String DOC_TYPE_BY_DOC_BASE_TYPE_SQL = "SELECT C_DocType_ID FROM C_DocType WHERE AD_Client_ID=? AND DocBaseType=? AND IsActive='Y' ORDER BY IsDefault DESC, C_DocType_ID";
	
	/**	AR Invoices - ARI       */
	public static final String 	DOCTYPE_ARInvoice       = MDocType.DOCBASETYPE_ARInvoice;
	/**	AR Credit Memo          */
	public static final String 	DOCTYPE_ARCredit        = "ARC";
	/**	AR Receipt              */
	public static final String 	DOCTYPE_ARReceipt       = "ARR";
	/**	AR ProForma             */
	public static final String 	DOCTYPE_ARProForma      = "ARF";
	/**	AP Invoices             */
	public static final String 	DOCTYPE_APInvoice       = "API";
	/**	AP Credit Memo          */
	public static final String 	DOCTYPE_APCredit        = "APC";
	/**	AP Payment              */
	public static final String 	DOCTYPE_APPayment       = "APP";
	/**	CashManagement Bank Statement   */
	public static final String 	DOCTYPE_BankStatement   = "CMB";
	/**	CashManagement Cash Journals    */
	public static final String 	DOCTYPE_CashJournal     = "CMC";
	/**	CashManagement Allocations      */
	public static final String 	DOCTYPE_Allocation      = "CMA";
	/** Material Shipment       */
	public static final String 	DOCTYPE_MatShipment     = "MMS";
	/** Material Receipt        */
	public static final String 	DOCTYPE_MatReceipt      = "MMR";
	/** Material Inventory      */
	public static final String 	DOCTYPE_MatInventory    = "MMI";
	/** Material Movement       */
	public static final String 	DOCTYPE_MatMovement     = "MMM";
	/** Material Production     */
	public static final String 	DOCTYPE_MatProduction   = "MMP";
	/** Match Invoice           */
	public static final String 	DOCTYPE_MatMatchInv     = "MXI";
	/** Match PO                */
	public static final String 	DOCTYPE_MatMatchPO      = "MXP";
	/** GL Journal              */
	public static final String 	DOCTYPE_GLJournal       = "GLJ";
	/** Purchase Order          */
	public static final String 	DOCTYPE_POrder          = "POO";
	/** Sales Order             */
	public static final String 	DOCTYPE_SOrder          = "SOO";
	/** Project Issue           */
	public static final String	DOCTYPE_ProjectIssue	= "PJI";
	/** Purchase Requisition    */
	public static final String	DOCTYPE_PurchaseRequisition	= "POR";


	//  Posting Status - AD_Reference_ID=234     //
	/**	Document Status         */
	public static final String 	STATUS_NotPosted        = "N";
	/**	Document Status         */
	public static final String 	STATUS_NotBalanced      = "b";
	/**	Document Status         */
	public static final String 	STATUS_NotConvertible   = "c";
	/**	Document Status         */
	public static final String 	STATUS_PeriodClosed     = "p";
	/**	Document Status         */
	public static final String 	STATUS_InvalidAccount   = "i";
	/**	Document Status         */
	public static final String 	STATUS_PostPrepared     = "y";
	/**	Document Status         */
	public static final String 	STATUS_Posted           = "Y";
	/**	Document Status         */
	public static final String 	STATUS_Error            = "E";
	/** Document Status			*/
	public static final String	STATUS_Deferred			= "d";


	/**
	 *  Create Posting document
	 *	@param as accounting schema
	 *  @param AD_Table_ID Table ID of Documents
	 *  @param Record_ID record ID to load
	 *  @param trxName transaction name
	 *  @return new Posting Document instance or null
	 */
	public static Doc get (MAcctSchema as, int AD_Table_ID, int Record_ID, String trxName)
	{
		return DocManager.getDocument(as, AD_Table_ID, Record_ID, trxName);
	}	//	get

	/**
	 *  Create Posting document
	 *	@param as accounting schema
	 *  @param AD_Table_ID Table ID of Documents
	 *  @param rs ResultSet
	 *  @param trxName transaction name
	 *  @return new Posting Document instance or null
	 *  @throws AdempiereUserError
	 */
	public static Doc get (MAcctSchema as, int AD_Table_ID, ResultSet rs, String trxName)
	{
		return DocManager.getDocument(as, AD_Table_ID, rs, trxName);
	}   //  get

	/**
	 *  Post document immediately
	 * 	@param ass accounting schema
	 * 	@param AD_Table_ID	Transaction table
	 *  @param Record_ID    Record ID of this document
	 *  @param force        force posting
	 *  @param trxName	    transaction
	 *  @return null if the document was posted or error message
	 */
	public static String postImmediate (MAcctSchema[] ass,
		int AD_Table_ID, int Record_ID, boolean force, String trxName)
	{
		return DocManager.postDocument(ass, AD_Table_ID, Record_ID, force, true, false, trxName);
	}   //  post

	/**
	 * Manual posting by user
	 * @param WindowNo
	 * @param AD_Client_ID
	 * @param AD_Table_ID
	 * @param Record_ID
	 * @param force
	 * @return error message ( if any )
	 */
	public static String manualPosting (int WindowNo, int AD_Client_ID,
			int AD_Table_ID, int Record_ID, boolean force)
	{
		String error = null;
		MAcctSchema[] ass = MAcctSchema.getClientAcctSchema(Env.getCtx(), AD_Client_ID);
		Trx trx = Trx.get(Trx.createTrxName("ManulPosting"), true);
		trx.setDisplayName(Doc.class.getName()+"_manualPosting");
		try
		{
			//Costing: Post MatchPO before MR
			if (AD_Table_ID == MInOut.Table_ID)
			{
				MMatchPO[] matchPos  = MMatchPO.getInOut(Env.getCtx(), Record_ID, trx.getTrxName());
				for (MMatchPO matchPo : matchPos) 
				{
					if (!matchPo.isPosted())
					{
						error = postImmediate(ass, matchPo.get_Table_ID(), matchPo.get_ID(), force, matchPo.get_TrxName());
						if (!Util.isEmpty(error))
							break;
					}
				}	
			}
			if (Util.isEmpty(error))
			{
				error = postImmediate(ass, AD_Table_ID, Record_ID, force, trx.getTrxName());
			}
			//Costing: Post MatchInv after Invoice
			if (Util.isEmpty(error))
			{
				if (AD_Table_ID == MInvoice.Table_ID)
				{
					MMatchInv[] matchInvs = MMatchInv.getInvoice(Env.getCtx(), Record_ID, trx.getTrxName());
					for (MMatchInv matchInv : matchInvs) 
					{
						if (!matchInv.isPosted())
						{
							error = postImmediate(ass, matchInv.get_Table_ID(), matchInv.get_ID(), force, matchInv.get_TrxName());
							if (!Util.isEmpty(error))
								break;
						}
					}	
				}
			}
			if (Util.isEmpty(error))
			{
				trx.commit(true);
			}
			else
			{
				trx.rollback();
			}
		}
		catch (Throwable t)
		{
			trx.rollback();
			if(t instanceof SQLException sqlEx) {
				String messageError = DBException.getDefaultDBExceptionMessage(sqlEx);
				   if (messageError != null) 
				      return Msg.getMsg(Env.getCtx(), messageError);
			}
			return "@Error@ " + t.getLocalizedMessage();
		}
		finally
		{
			trx.close();
		}
		
		return error;
	}
	
	/**	Static Log						*/
	protected static final CLogger	s_log = CLogger.getCLogger(Doc.class);
	/**	Log	per Document				*/
	protected transient CLogger			log = CLogger.getCLogger(getClass());

	/* If the transaction must be managed locally (false if it's managed externally by the caller) */
	private boolean m_manageLocalTrx;


	/**
	 *  Constructor
	 * 	@param as accounting schema
	 * 	@param clazz Document Class
	 * 	@param rs result set
	 * 	@param defaultDocumentType default document type or null
	 * 	@param trxName trx
	 */
	public Doc (MAcctSchema as, Class<?> clazz, ResultSet rs, String defaultDocumentType, String trxName)
	{
		p_Status = STATUS_Error;
		m_as = as;
		m_ctx = new Properties(m_as.getCtx());
		m_ctx.setProperty(Env.AD_CLIENT_ID, String.valueOf(m_as.getAD_Client_ID()));

		String className = clazz.getName();
		className = className.substring(className.lastIndexOf('.')+1);
		try
		{
			Constructor<?> constructor = clazz.getConstructor(new Class[]{Properties.class, ResultSet.class, String.class});
			p_po = (PO)constructor.newInstance(new Object[]{m_ctx, rs, trxName});
		}
		catch (Exception e)
		{
			String msg = className + ": " + e.getLocalizedMessage();
			log.severe(msg);
			throw new IllegalArgumentException(msg);
		}
		p_po.load(p_po.get_TrxName()); // reload the PO to get any virtual column that was not obtained using the rs (IDEMPIERE-775)

		//	DocStatus
		int index = p_po.get_ColumnIndex("DocStatus");
		if (index != -1)
			m_DocStatus = (String)p_po.get_Value(index);

		//	Document Type
		setDocumentType (defaultDocumentType);
		m_trxName = trxName;
		m_manageLocalTrx = false;
		if (m_trxName == null)
		{
			m_trxName = "Post" + m_DocumentType + p_po.get_ID();
			m_manageLocalTrx = true;
		}
		p_po.set_TrxName(m_trxName);

		//	Amounts
		for(int i = 0; i < m_Amounts.length; i++)
		{
			m_Amounts[i] = Env.ZERO;
		}
	}   //  Doc

	/** Accounting Schema */
	private MAcctSchema    		m_as = null;
	/** Properties					*/
	private Properties			m_ctx = null;
	/** Transaction Name			*/
	private String				m_trxName = null;
	/** The Document				*/
	protected PO				p_po = null;
	/** Document Type      			*/
	private String				m_DocumentType = null;
	/** Document Status      			*/
	private String				m_DocStatus = null;
	/** Document No      			*/
	private String				m_DocumentNo = null;
	/** Description      			*/
	private String				m_Description = null;
	/** GL Category      			*/
	private int					m_GL_Category_ID = 0;
	/** GL Period					*/
	private MPeriod 			m_period = null;
	/** Period ID					*/
	private int					m_C_Period_ID = 0;
	/** Location From				*/
	private int					m_C_LocFrom_ID = 0;
	/** Location To					*/
	private int					m_C_LocTo_ID = 0;
	/** Accounting Date				*/
	private Timestamp			m_DateAcct = null;
	/** Document Date				*/
	private Timestamp			m_DateDoc = null;
	/** Tax Included				*/
	private boolean				m_TaxIncluded = false;
	/** Is (Source) Multi-Currency Document - i.e. the document has different currencies
	 *  (if true, the document will not be source balanced)     */
	private boolean				m_MultiCurrency = false;
	/** BP Sales Region    			*/
	private int					m_BP_C_SalesRegion_ID = -1;
	/** B Partner	    			*/
	private int					m_C_BPartner_ID = -1;
	/** Department */
	private int					m_C_Department_ID		= -1;
	/** Cost Center */
	private int					m_C_CostCenter_ID		= -1;
	/** B Partner Employee */
	private int					m_C_Employee_ID	= -1;
	/** Asset */
	private int					m_A_Asset_ID			= -1;
	/** Bank Account				*/
	private int 				m_C_BankAccount_ID = -1;
	/** Cach Book					*/
	private int 				m_C_CashBook_ID = -1;
	/** Currency					*/
	private int					m_C_Currency_ID = -1;

	/**	Contained Doc Lines			*/
	protected DocLine[]			p_lines;

	/** Facts                       */
	private ArrayList<Fact>    	m_fact = null;

	/** No Currency in Document Indicator (-1)	*/
	protected static final int  NO_CURRENCY = -2;

	/**	Actual Document Status  */
	protected String			p_Status = null;

	public String getPostStatus()
	{
		return p_Status;
	}

	/**
	 * Document is Post if the document type is always posted or if the accounting date is within
	 * the schema's valid range.
	 * 
	 * @return {@code true} if the document type is always posted or if the accounting date is
	 *         within the
	 *         valid range of the accounting schema; {@code false} otherwise.
	 */
	public boolean isPostForAcctSchema()
	{
		return isAlwaysPosted() || m_as.isAcctDateInRange(getDateAcct());
	} // isPostForAcctSchema

	/**
	 * Checks if the document type is configured to always be posted.
	 * 
	 * @return {@code true} if the document type has the "always posted" flag enabled; {@code false} otherwise.
	 */
	public boolean isAlwaysPosted()
	{
	    return getC_DocType_ID() > 0 && MDocType.get(getC_DocType_ID()).isAlwaysPosted();
	}// isAlwaysPosted

	/** Error Message			*/
	protected String			p_Error = null;

	/**
	 * 	Get Context
	 *	@return context
	 */
	public Properties getCtx()
	{
		return m_ctx;
	}	//	getCtx

	/**
	 * 	Get Table Name
	 *	@return table name
	 */
	public String get_TableName()
	{
		return p_po.get_TableName();
	}	//	get_TableName

	/**
	 * 	Get Table ID
	 *	@return table id
	 */
	public int get_Table_ID()
	{
		return p_po.get_Table_ID();
	}	//	get_Table_ID

	/**
	 * 	Get Record_ID
	 *	@return record id
	 */
	public int get_ID()
	{
		return p_po.get_ID();
	}	//	get_ID

	/**
	 * 	Get Persistent Object
	 *	@return po
	 */
	public PO getPO()
	{
		return p_po;
	}	//	getPO

	/**
	 *  Post Document.
	 *  <pre>
	 *  - try to lock document (Processed='Y' (AND Processing='N' AND Posted='N'))
	 *       - if not ok - return false
	 *          - postlogic (for all Accounting Schema)
	 *              - create Fact lines
	 *          - postCommit
	 *              - commits Fact lines and Document and sets Processing = 'N'
	 *              - if error - create Note
	 *  </pre>
	 *  @param force if true ignore that locked
	 *  @param repost if true ignore that already posted
	 *  @return error message or null
	 */
	public final String post (boolean force, boolean repost)
	{
		return post (force, repost, false);
	}
	
	/**
	 * Post Document
	 * @param force	if true ignore that locked
	 * @param repost if true ignore that already posted
	 * @param isInBackDatePostingProcess if true is in a back-date posting process
	 * @return error message or null
	 */
	public final String post (boolean force, boolean repost, boolean isInBackDatePostingProcess)
	{
		this.isInBackDatePostingProcess = isInBackDatePostingProcess;
		
		if (m_DocStatus == null)
			;	//	return "No DocStatus for DocumentNo=" + getDocumentNo();
		else if (m_DocStatus.equals(DocumentEngine.STATUS_Completed)
			|| m_DocStatus.equals(DocumentEngine.STATUS_Closed)
			|| m_DocStatus.equals(DocumentEngine.STATUS_Voided)
			|| m_DocStatus.equals(DocumentEngine.STATUS_Reversed))
			;
		else{
			StringBuilder msgreturn = new StringBuilder("Invalid DocStatus='").append(m_DocStatus).append("' for DocumentNo=").append(getDocumentNo());
			return msgreturn.toString();
		}	
		//
		if (p_po.getAD_Client_ID() != m_as.getAD_Client_ID())
		{
			StringBuilder error = new StringBuilder("AD_Client_ID Conflict - Document=").append(p_po.getAD_Client_ID())
				.append(", AcctSchema=").append(m_as.getAD_Client_ID());
			log.severe(error.toString());
			return error.toString();
		}

		//  Lock Record ----
		String trxName = null;	//	outside trx if on server
		if (! m_manageLocalTrx)
			trxName = getTrxName(); // on trx if it's in client
		StringBuilder sql = new StringBuilder ("UPDATE ");
		sql.append(get_TableName()).append( " SET Processing='Y' WHERE ")
			.append(get_TableName()).append("_ID=").append(get_ID())
			.append(" AND Processed='Y' AND IsActive='Y'");
		if (!force)
			sql.append(" AND (Processing='N' OR Processing IS NULL)");
		if (!repost)
			sql.append(" AND Posted IN ('N','d')");
		if (DB.executeUpdate(sql.toString(), trxName) == 1) {
			if (log.isLoggable(Level.INFO)) log.info("Locked: " + get_TableName() + "_ID=" + get_ID());
		} else {
			log.log(Level.SEVERE, "Resubmit - Cannot lock " + get_TableName() + "_ID="
				+ get_ID() + ", Force=" + force + ",RePost=" + repost);
			if (!p_po.isActive())
				return Msg.translate(getCtx(), "CannotPostInactiveDocument");
			if (force)
				return Msg.translate(getCtx(), "CannotLockReSubmit");
			return  Msg.translate(getCtx(), "CannotLockReSubmitOrRePostWithForce");
		}

		p_Error = loadDocumentDetails();
		if (p_Error != null)
			return p_Error;
		if (isDeferPosting())
		{
			unlock();
			p_Status = STATUS_Deferred;
			return null;
		}			

		Trx trx = Trx.get(getTrxName(), true);
		//  Delete existing Accounting
		if (repost)
		{
			if (isPosted() && !isPeriodOpen())	//	already posted - don't delete if period closed
			{
				log.log(Level.SEVERE, toString() + " - Period Closed for already posted document");
				unlock();
				trx.commit(); trx.close();
				return "PeriodClosed";
			}
			//	delete it
			deleteAcct();
		}
		else if (isPosted())
		{
			log.log(Level.SEVERE, toString() + " - Document already posted");
			unlock();
			trx.commit(); trx.close();
			return "AlreadyPosted";
		}

		p_Status = STATUS_NotPosted;

		//  Create Fact per AcctSchema
		m_fact = new ArrayList<Fact>();

		getPO().setDoc(this);
		try
		{
			//	if acct schema has "only" org, skip
			boolean skip = false;
			if (m_as.getAD_OrgOnly_ID() != 0)
			{
				//	Header Level Org
				skip = m_as.isSkipOrg(getAD_Org_ID());
				//	Line Level Org
				if (p_lines != null)
				{
					for (int line = 0; skip && line < p_lines.length; line++)
					{
						skip = m_as.isSkipOrg(p_lines[line].getAD_Org_ID());
						if (!skip)
							break;
					}
				}
			}

			if (!skip && !isPostForAcctSchema())
				skip = true;

			if (!skip)
			{
				//	post
				p_Status = postLogic ();
			}
			else
			{
				p_Status = STATUS_Posted; // skipped is OK
			}
		}
		catch (Exception e)
		{
			log.log(Level.SEVERE, "", e);
			p_Status = STATUS_Error;
			p_Error = e.toString();
		}

		String validatorMsg = null;
		// Call validator on before post
		if (p_Status.equals(STATUS_Posted)) {
			validatorMsg = ModelValidationEngine.get().fireDocValidate(getPO(), ModelValidator.TIMING_BEFORE_POST);
			if (validatorMsg != null) {
				p_Status = STATUS_Error;
				p_Error = validatorMsg;
			}
		}

		//  commitFact
		p_Status = postCommit (p_Status);

		if (p_Status.equals(STATUS_Posted)) {
			validatorMsg = ModelValidationEngine.get().fireDocValidate(getPO(), ModelValidator.TIMING_AFTER_POST);
			if (validatorMsg != null) {
				p_Status = STATUS_Error;
				p_Error = validatorMsg;
			}
		}

		//  Create Note
		if (!p_Status.equals(STATUS_Posted) && !p_Status.equals(STATUS_Deferred))
		{
			//  Insert Note
			SimpleDateFormat dateFormat = DisplayType.getDateFormat(DisplayType.Date);
			DecimalFormat numberFormat = DisplayType.getNumberFormat(DisplayType.Amount);
			String AD_MessageValue = "PostingError-" + p_Status;
			int AD_User_ID = p_po.getUpdatedBy();
			MNote note = new MNote (getCtx(), AD_MessageValue, AD_User_ID,
				getAD_Client_ID(), getAD_Org_ID(), null);
			note.setRecord(p_po.get_Table_ID(), p_po.get_ID());
			//  Reference
			note.setReference(toString());	//	Document
			//	Text
			StringBuilder Text = new StringBuilder (Msg.getMsg(Env.getCtx(), AD_MessageValue));
			if (p_Error != null)
				Text.append(" (").append(p_Error).append(")");
			String cn = getClass().getName();
			Text.append(" - ").append(cn.substring(cn.lastIndexOf('.')))
			.append(" (").append(getDocumentType())
			.append(" - " + Msg.getElement(Env.getCtx(),"DocumentNo") + "=").append(getDocumentNo())
			.append(" - " + Msg.getElement(Env.getCtx(),"DateAcct") + "=").append(dateFormat.format(getDateAcct()))
			.append(" - " + Msg.getMsg(Env.getCtx(),"Amount") + "=").append(numberFormat.format(getAmount()))
			.append(" - " + Msg.getElement(Env.getCtx(),"DocStatus") + "=").append(MRefList.getListName(getCtx(), 131, m_DocStatus))
			.append(" - " + Msg.getMsg(Env.getCtx(),"PeriodOpen") + "=").append(Msg.getMsg(Env.getCtx(), String.valueOf(isPeriodOpen())))
			.append(" - " + Msg.getElement(Env.getCtx(),"IsBalanced") + "=").append( Msg.getMsg(Env.getCtx(), String.valueOf(isBalanced())))
			.append(" - " + Msg.getElement(Env.getCtx(),"C_AcctSchema_ID") + "=").append(m_as.getName());
			note.setTextMsg(Text.toString());
			try {
				note.saveEx();
			} catch (AdempiereException e) {
				if (e.getMessage() != null && e.getMessage().startsWith("Foreign ID " + p_po.get_ID() + " not found in ")) {
					; //ignore, in unit test
				} else {
					throw e;
				}
			}
			p_Error = Text.toString();
		}

		//  dispose facts
		for (int i = 0; i < m_fact.size(); i++)
		{
			Fact fact = m_fact.get(i);
			if (fact != null)
				fact.dispose();
		}
		p_lines = null;

		if (p_Status.equals(STATUS_Posted))
			return null;
		return p_Error;
	}   //  post

	/**
	 * 	Delete fact records
	 *	@return number of records deleted
	 */
	protected int deleteAcct()
	{				
		// backup the posting records before delete them
		StringBuilder sql = new StringBuilder ("INSERT INTO T_Fact_Acct_History ")
				.append("SELECT * FROM Fact_Acct ")
				.append("WHERE AD_Table_ID=?")
				.append(" AND Record_ID=?")
				.append(" AND C_AcctSchema_ID=?");
		int no = DB.executeUpdate(sql.toString(), new Object[] {get_Table_ID(), p_po.get_ID(), m_as.getC_AcctSchema_ID()}, false, getTrxName());
		if (no != 0)
			if (log.isLoggable(Level.INFO)) log.info("inserted=" + no);
		
		// set the updated to current time - for house keeping purpose
		sql = new StringBuilder ("UPDATE T_Fact_Acct_History ")
				.append("SET Updated=? ")
				.append("WHERE Created=Updated ")
				.append(" AND AD_Table_ID=?")
				.append(" AND Record_ID=?")
				.append(" AND C_AcctSchema_ID=?");
		no = DB.executeUpdate(sql.toString(), 
				new Object[] {new Timestamp(System.currentTimeMillis()), get_Table_ID(), p_po.get_ID(), m_as.getC_AcctSchema_ID()}, 
				false, getTrxName());
		if (no != 0)
			if (log.isLoggable(Level.INFO)) log.info("updated=" + no);
		
		// delete the posting records
		sql = new StringBuilder ("DELETE FROM Fact_Acct ")
			.append("WHERE AD_Table_ID=?")
			.append(" AND Record_ID=?")
			.append(" AND C_AcctSchema_ID=?");
		no = DB.executeUpdate(sql.toString(), new Object[] {get_Table_ID(), p_po.get_ID(), m_as.getC_AcctSchema_ID()}, false, getTrxName());
		if (no != 0)
			if (log.isLoggable(Level.INFO)) log.info("deleted=" + no);
		return no;
	}	//	deleteAcct

	/**
	 *  Posting logic for Accounting Schema
	 *  @return posting status/error code
	 */
	private final String postLogic ()
	{
		//  rejectUnbalanced
		if (!m_as.isSuspenseBalancing() && !isBalanced())
			return STATUS_NotBalanced;

		//  rejectUnconvertible
		if (!isConvertible(m_as))
			return STATUS_NotConvertible;

		//  rejectPeriodClosed
		if (!isPeriodOpen())
			return STATUS_PeriodClosed;

		//  createFacts
		ArrayList<Fact> facts = createFacts (m_as);
		if (facts == null)
			return STATUS_Error;

		// call modelValidator
		String validatorMsg = ModelValidationEngine.get().fireFactsValidate(m_as, facts, getPO());
		if (validatorMsg != null) {
			p_Error = validatorMsg;
			return STATUS_Error;
		}

		for (int f = 0; f < facts.size(); f++)
		{
			Fact fact = facts.get(f);
			if (fact == null)
				return STATUS_Error;
			m_fact.add(fact);
			//
			p_Status = STATUS_PostPrepared;

			//	check accounts
			if (!fact.checkAccounts())
				return STATUS_InvalidAccount;

			//	distribute
			if (!fact.distribute())
				return STATUS_Error;

			//  balanceSource
			if (!fact.isSourceBalanced())
			{
				fact.balanceSource();
				if (!fact.isSourceBalanced())
					return STATUS_NotBalanced;
			}

			//  balanceSegments
			if (!fact.isSegmentBalanced())
			{
				fact.balanceSegments();
				if (!fact.isSegmentBalanced())
					return STATUS_NotBalanced;
			}

			//  balanceAccounting
			if (!fact.isAcctBalanced())
			{
				fact.balanceAccounting();
				if (!fact.isAcctBalanced())
					return STATUS_NotBalanced;
			}

		}	//	for all facts

		return STATUS_Posted;
	}   //  postLogic

	/**
	 *  Post Commit. <br/>
	 *  Save Facts & Document
	 *  @param status status
	 *  @return Posting Status
	 */
	private final String postCommit (String status)
	{
		if (log.isLoggable(Level.INFO)) log.info("Sta=" + status + " DT=" + getDocumentType()
			+ " ID=" +  p_po.get_ID());
		p_Status = status;

		Trx trx = Trx.get(getTrxName(), true);
		try
		{
		//  *** Transaction Start       ***
			//  Commit Facts
			if (status.equals(STATUS_Posted))
			{
				for (int i = 0; i < m_fact.size(); i++)
				{
					Fact fact = m_fact.get(i);
					if (fact == null)
						;
					else if (fact.save(getTrxName()))
						;
					else
					{
						log.log(Level.SEVERE, "(fact not saved) ... rolling back");
						if (m_manageLocalTrx) {
							trx.rollback();
							trx.close();
						}
						unlock();
						return STATUS_Error;
					}
				}
			}

			unlock();

			//	Success
			if (m_manageLocalTrx) {
				trx.commit(true);
				trx.close();
				trx = null;
			}
		//  *** Transaction End         ***
		}
		catch (Exception e)
		{
			log.log(Level.SEVERE, "... rolling back", e);
			status = STATUS_Error;
			if (m_manageLocalTrx) {
				try {
					if (trx != null)
						trx.rollback();
				} catch (Exception e2) {}
				try {
					if (trx != null)
						trx.close();
					trx = null;
				} catch (Exception e3) {}
			}
			unlock();
		}
		p_Status = status;
		return status;
	}   //  postCommit

	/**
	 * 	Get Trx Name
	 *	@return Trx Name
	 */
	public String getTrxName()
	{
		return m_trxName;
	}	//	getTrxName

	/**
	 *  Unlock Document
	 */
	private void unlock()
	{
		String trxName = null;	//	outside trx if on server
		if (! m_manageLocalTrx)
			trxName = getTrxName(); // on trx if it's in client
		StringBuilder sql = new StringBuilder ("UPDATE ");
		sql.append(get_TableName()).append( " SET Processing='N' WHERE ")
			.append(get_TableName()).append("_ID=").append(p_po.get_ID());
		DB.executeUpdate(sql.toString(), trxName);
	}   //  unlock


	/**
	 *  Load Document Type and GL Info.
	 * 	Set p_DocumentType and p_GL_Category_ID
	 * 	@return document type (i.e. C_DocType.DocBaseType)
	 */
	public String getDocumentType()
	{
		if (m_DocumentType == null)
			setDocumentType(null);
		return m_DocumentType;
	}   //  getDocumentType

	/**
	 *  Load Document Type and GL Info.
	 * 	Set p_DocumentType and p_GL_Category_ID
	 *	@param DocumentType
	 */
	public void setDocumentType (String DocumentType)
	{
		if (DocumentType != null)
			m_DocumentType = DocumentType;
		//  IDEMPIERE-3342 - prefer the category defined for the doctype if there is such column in the table
		if (p_po.get_ColumnIndex("C_DocType_ID") >= 0 && getC_DocType_ID() != 0)
		{
			String sql = "SELECT DocBaseType, GL_Category_ID FROM C_DocType WHERE C_DocType_ID=?";
			PreparedStatement pstmt = null;
			ResultSet rsDT = null;
			try
			{
				pstmt = DB.prepareStatement(sql, null);
				pstmt.setInt(1, getC_DocType_ID());
				rsDT = pstmt.executeQuery();
				if (rsDT.next())
				{
					m_DocumentType = rsDT.getString(1);
					m_GL_Category_ID = rsDT.getInt(2);
				}
			}
			catch (SQLException e)
			{
				log.log(Level.SEVERE, sql, e);
			}
			finally
			{
				DB.close(rsDT, pstmt);
				rsDT = null;
				pstmt = null;
			}
		}
		if (m_DocumentType == null)
		{
			log.log(Level.SEVERE, "No DocBaseType for C_DocType_ID="
				+ getC_DocType_ID() + ", DocumentNo=" + getDocumentNo());
		}

		//  We have a document Type, but no GL info - search for DocType
		if (m_GL_Category_ID == 0)
		{
			String sql = "SELECT GL_Category_ID FROM C_DocType "
					+ "WHERE AD_Client_ID=? AND DocBaseType=?";
			PreparedStatement pstmt = null;
			ResultSet rsDT = null;
			try
			{
				pstmt = DB.prepareStatement(sql.toString(), null);
				pstmt.setInt(1, getAD_Client_ID());
				pstmt.setString(2, m_DocumentType);
				rsDT = pstmt.executeQuery();
				if (rsDT.next())
					m_GL_Category_ID = rsDT.getInt(1);
			}
			catch (SQLException e)
			{
				log.log(Level.SEVERE, sql, e);
			}
			finally
			{
				DB.close(rsDT, pstmt);
				rsDT = null;
				pstmt = null;
			}
		}

		//  Still no GL_Category - get Default GL Category
		if (m_GL_Category_ID == 0)
		{
			String sql = "SELECT GL_Category_ID FROM GL_Category "
				+ "WHERE AD_Client_ID=? "
				+ "ORDER BY IsDefault DESC";
			PreparedStatement pstmt = null;
			ResultSet rsDT = null;
			try
			{
				pstmt = DB.prepareStatement(sql, null);
				pstmt.setInt(1, getAD_Client_ID());
				rsDT = pstmt.executeQuery();
				if (rsDT.next())
					m_GL_Category_ID = rsDT.getInt(1);
			}
			catch (SQLException e)
			{
				log.log(Level.SEVERE, sql, e);
			}
			finally
			{
				DB.close(rsDT, pstmt);
				rsDT = null;
				pstmt = null;
			}			
		}
		//
		if (m_GL_Category_ID == 0)
			log.log(Level.SEVERE, "No default GL_Category - " + toString());

		if (m_DocumentType == null)
			throw new IllegalStateException("Document Type not found");
	}	//	setDocumentType


	/**
	 *  Is the Source Document Balanced
	 *  @return true if (source) balanced
	 */
	public boolean isBalanced()
	{
		//  Multi-Currency documents are source balanced by definition
		if (isMultiCurrency())
			return true;
		//
		boolean retValue = getBalance().signum() == 0;
		if (retValue) {
			if (log.isLoggable(Level.FINE)) log.fine("Yes " + toString());
		} else {
			log.warning("NO - " + toString());
		}
		return retValue;
	}	//	isBalanced

	/**
	 *  Is Document convertible to currency and Conversion Type
	 *  @param acctSchema accounting schema
	 *  @return true, if convertible to accounting currency
	 */
	public boolean isConvertible (MAcctSchema acctSchema)
	{
		//  No Currency in document
		if (getC_Currency_ID() == NO_CURRENCY)
		{
			if (log.isLoggable(Level.FINE)) log.fine("(none) - " + toString());
			return true;
		}
		// Journal from a different acct schema
		if (this instanceof Doc_GLJournal) {
			int glj_as = ((Integer) p_po.get_Value("C_AcctSchema_ID")).intValue();
			if (acctSchema.getC_AcctSchema_ID() != glj_as)
				return true;
		}
		//  Get All Currencies
		HashSet<Integer> set = new HashSet<Integer>();
		set.add(Integer.valueOf(getC_Currency_ID()));
		for (int i = 0; p_lines != null && i < p_lines.length; i++)
		{
			int C_Currency_ID = p_lines[i].getC_Currency_ID();
			if (C_Currency_ID != NO_CURRENCY)
				set.add(Integer.valueOf(C_Currency_ID));
		}

		//  just one and the same
		if (set.size() == 1 && acctSchema.getC_Currency_ID() == getC_Currency_ID())
		{
			if (log.isLoggable(Level.FINE)) log.fine("(same) Cur=" + getC_Currency_ID() + " - " + toString());
			return true;
		}

		boolean convertible = true;
		Iterator<Integer> it = set.iterator();
		while (it.hasNext() && convertible)
		{
			int C_Currency_ID = it.next().intValue();
			if (C_Currency_ID != acctSchema.getC_Currency_ID())
			{
				BigDecimal amt = MConversionRate.getRate (C_Currency_ID, acctSchema.getC_Currency_ID(),
					getDateAcct(), getC_ConversionType_ID(), getAD_Client_ID(), getAD_Org_ID());
				if (amt == null)
				{
					convertible = false;
					log.warning ("NOT from C_Currency_ID=" + C_Currency_ID
						+ " to " + acctSchema.getC_Currency_ID()
						+ " - " + toString());
				}
				else
					if (log.isLoggable(Level.FINE)) log.fine("From C_Currency_ID=" + C_Currency_ID);
			}
		}

		if (log.isLoggable(Level.FINE)) log.fine("Convertible=" + convertible + ", AcctSchema C_Currency_ID=" + acctSchema.getC_Currency_ID() + " - " + toString());
		return convertible;
	}	//	isConvertible

	/**
	 *  Calculate Period from DateAcct.
	 *  m_C_Period_ID is set to -1 if not open, to 0 if not found
	 */
	public void setPeriod()
	{
		if (m_period != null)
			return;

		//	Period defined in GL Journal (e.g. adjustment period)
		int index = p_po.get_ColumnIndex("C_Period_ID");
		if (index != -1)
		{
			Integer ii = (Integer)p_po.get_Value(index);
			if (ii != null)
				m_period = MPeriod.get(getCtx(), ii.intValue());
		}
		if (m_period == null)
			m_period = MPeriod.get(getCtx(), getDateAcct(), getAD_Org_ID(), (String)null);
		//	Is Period Open?
		if (m_period != null
			&& m_period.isOpen(getDocumentType(), getDateAcct(), true))
			m_C_Period_ID = m_period.getC_Period_ID();
		else
			m_C_Period_ID = -1;
		//
		if (log.isLoggable(Level.FINE)) log.fine(	// + AD_Client_ID + " - "
			getDateAcct() + " - " + getDocumentType() + " => " + m_C_Period_ID);
	}   //  setC_Period_ID

	/**
	 * 	Get C_Period_ID
	 *	@return C_Period_ID
	 */
	public int getC_Period_ID()
	{
		if (m_period == null)
			setPeriod();
		return m_C_Period_ID;
	}	//	getC_Period_ID

	/**
	 *	Is Period Open
	 *  @return true if period is open
	 */
	public boolean isPeriodOpen()
	{
		setPeriod();
		boolean open = m_C_Period_ID > 0;
		if (open) {
			if (log.isLoggable(Level.FINE)) log.fine("Yes - " + toString());
		} else {
			log.warning("NO - " + toString());
		}
		return open;
	}	//	isPeriodOpen

	/**	Amount Type - Invoice - Gross   */
	public static final int 	AMTTYPE_Gross   = 0;
	/**	Amount Type - Invoice - Net   */
	public static final int 	AMTTYPE_Net     = 1;
	/**	Amount Type - Invoice - Charge   */
	public static final int 	AMTTYPE_Charge  = 2;

	/** Source Amounts (may not all be used)	*/
	private BigDecimal[]		m_Amounts = new BigDecimal[4];
	/** Quantity								*/
	private BigDecimal			m_qty = null;

	/**
	 *	Get the Amount
	 *  (loaded in loadDocumentDetails)
	 *
	 *  @param AmtType see AMTTYPE_*
	 *  @return Amount
	 */
	public BigDecimal getAmount(int AmtType)
	{
		if (AmtType < 0 || AmtType >= m_Amounts.length)
			return null;
		return m_Amounts[AmtType];
	}	//	getAmount

	/**
	 *	Set the Amount
	 *  @param AmtType see AMTTYPE_*
	 *  @param amt Amount
	 */
	public void setAmount(int AmtType, BigDecimal amt)
	{
		if (AmtType < 0 || AmtType >= m_Amounts.length)
			return;
		if (amt == null)
			m_Amounts[AmtType] = Env.ZERO;
		else
			m_Amounts[AmtType] = amt;
	}	//	setAmount

	/**
	 *  Get Amount with index 0
	 *  @return Amount (primary document amount)
	 */
	public BigDecimal getAmount()
	{
		return m_Amounts[0];
	}   //  getAmount

	/**
	 *  Set Quantity
	 *  @param qty Quantity
	 */
	public void setQty (BigDecimal qty)
	{
		m_qty = qty;
	}   //  setQty

	/**
	 *  Get Quantity
	 *  @return Quantity
	 */
	public BigDecimal getQty()
	{
		if (m_qty == null)
		{
			int index = p_po.get_ColumnIndex("Qty");
			if (index != -1)
				m_qty = (BigDecimal)p_po.get_Value(index);
			else
				m_qty = Env.ZERO;
		}
		return m_qty;
	}   //  getQty

	/**	Account Type - Invoice - Charge  */
	public static final int 	ACCTTYPE_Charge         = 0;
	/**	Account Type - Invoice - AR  */
	public static final int 	ACCTTYPE_C_Receivable   = 1;
	/**	Account Type - Invoice - AP  */
	public static final int 	ACCTTYPE_V_Liability    = 2;
	/**	Account Type - Invoice - AP Service  */
	public static final int 	ACCTTYPE_V_Liability_Services    = 3; // Deprecated IDEMPIERE-362
	/**	Account Type - Invoice - AR Service  */
	public static final int 	ACCTTYPE_C_Receivable_Services   = 4; // Deprecated IDEMPIERE-362

	/** Account Type - Payment - Unallocated */
	public static final int     ACCTTYPE_UnallocatedCash = 10;
	/** Account Type - Payment - Transfer */
	public static final int 	ACCTTYPE_BankInTransit  = 11;
	/** Account Type - Payment - Selection */
	public static final int     ACCTTYPE_PaymentSelect  = 12;
	/** Account Type - Payment - Prepayment */
	public static final int 	ACCTTYPE_C_Prepayment  = 13;
	/** Account Type - Payment - Prepayment */
	public static final int     ACCTTYPE_V_Prepayment  = 14;

	/** Account Type - Cash     - Asset */
	public static final int     ACCTTYPE_CashAsset = 20;
	/** Account Type - Cash     - Transfer */
	public static final int     ACCTTYPE_CashTransfer = 21;
	/** Account Type - Cash     - Expense */
	public static final int     ACCTTYPE_CashExpense = 22;
	/** Account Type - Cash     - Receipt */
	public static final int     ACCTTYPE_CashReceipt = 23;
	/** Account Type - Cash     - Difference */
	public static final int     ACCTTYPE_CashDifference = 24;

	/** Account Type - Allocation - Discount Expense (AR) */
	public static final int 	ACCTTYPE_DiscountExp = 30;
	/** Account Type - Allocation - Discount Revenue (AP) */
	public static final int 	ACCTTYPE_DiscountRev = 31;
	/** Account Type - Allocation  - Write Off */
	public static final int 	ACCTTYPE_WriteOff = 32;

	/** Account Type - Bank Statement - Asset  */
	public static final int     ACCTTYPE_BankAsset = 40;
	/** Account Type - Bank Statement - Interest Revenue */
	public static final int     ACCTTYPE_InterestRev = 41;
	/** Account Type - Bank Statement - Interest Exp  */
	public static final int     ACCTTYPE_InterestExp = 42;

	/** Inventory Accounts  - Differences	*/
	public static final int     ACCTTYPE_InvDifferences = 50;
	/** Inventory Accounts - NIR		*/
	public static final int     ACCTTYPE_NotInvoicedReceipts = 51;

	/** Project Accounts - Assets      	*/
	public static final int     ACCTTYPE_ProjectAsset = 61;
	/** Project Accounts - WIP         	*/
	public static final int     ACCTTYPE_ProjectWIP = 62;

	/** GL Accounts - PPV Offset		*/
	public static final int     ACCTTYPE_PPVOffset = 101;
	/** GL Accounts - Commitment Offset	*/
	public static final int     ACCTTYPE_CommitmentOffset = 111;
	/** GL Accounts - Commitment Offset	Sales */
	public static final int     ACCTTYPE_CommitmentOffsetSales = 112;

	/**
	 *	Get valid combination id by account type and accounting schema
	 *  @param AcctType see ACCTTYPE_*
	 *  @param as accounting schema
	 *  @return C_ValidCombination_ID
	 */
	public int getValidCombination_ID (int AcctType, MAcctSchema as)
	{
		int para_1 = 0;     //  first parameter (second is always AcctSchema)
		String sql = null;

		/**	Account Type - Invoice */
		if (AcctType == ACCTTYPE_Charge)	//	see getChargeAccount in DocLine
		{
			int cmp = getAmount(AMTTYPE_Charge).compareTo(Env.ZERO);
			if (cmp == 0)
				return 0;
			else
				sql = "SELECT CH_Expense_Acct FROM C_Charge_Acct WHERE C_Charge_ID=? AND C_AcctSchema_ID=?";
			
			para_1 = getC_Charge_ID();
		}
		else if (AcctType == ACCTTYPE_V_Liability)
		{
			sql = "SELECT V_Liability_Acct FROM C_BP_Vendor_Acct WHERE C_BPartner_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_BPartner_ID();
		}
		else if (AcctType == ACCTTYPE_V_Liability_Services) // Deprecated IDEMPIERE-362
		{
			sql = "SELECT V_Liability_Services_Acct FROM C_BP_Vendor_Acct WHERE C_BPartner_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_BPartner_ID();
		}
		else if (AcctType == ACCTTYPE_C_Receivable)
		{
			sql = "SELECT C_Receivable_Acct FROM C_BP_Customer_Acct WHERE C_BPartner_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_BPartner_ID();
		}
		else if (AcctType == ACCTTYPE_C_Receivable_Services) // Deprecated IDEMPIERE-362
		{
			sql = "SELECT C_Receivable_Services_Acct FROM C_BP_Customer_Acct WHERE C_BPartner_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_BPartner_ID();
		}
		else if (AcctType == ACCTTYPE_V_Prepayment)
		{
			sql = "SELECT V_Prepayment_Acct FROM C_BP_Vendor_Acct WHERE C_BPartner_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_BPartner_ID();
		}
		else if (AcctType == ACCTTYPE_C_Prepayment)
		{
			sql = "SELECT C_Prepayment_Acct FROM C_BP_Customer_Acct WHERE C_BPartner_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_BPartner_ID();
		}

		/** Account Type - Payment  */
		else if (AcctType == ACCTTYPE_UnallocatedCash)
		{
			sql = "SELECT B_UnallocatedCash_Acct FROM C_BankAccount_Acct WHERE C_BankAccount_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_BankAccount_ID();
		}
		else if (AcctType == ACCTTYPE_BankInTransit)
		{
			sql = "SELECT B_InTransit_Acct FROM C_BankAccount_Acct WHERE C_BankAccount_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_BankAccount_ID();
		}
		else if (AcctType == ACCTTYPE_PaymentSelect)
		{
			sql = "SELECT B_PaymentSelect_Acct FROM C_BankAccount_Acct WHERE C_BankAccount_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_BankAccount_ID();
		}

		/** Account Type - Allocation   */
		else if (AcctType == ACCTTYPE_DiscountExp)
		{
			sql = "SELECT a.PayDiscount_Exp_Acct FROM C_BP_Group_Acct a, C_BPartner bp "
				+ "WHERE a.C_BP_Group_ID=bp.C_BP_Group_ID AND bp.C_BPartner_ID=? AND a.C_AcctSchema_ID=?";
			para_1 = getC_BPartner_ID();
		}
		else if (AcctType == ACCTTYPE_DiscountRev)
		{
			sql = "SELECT PayDiscount_Rev_Acct FROM C_BP_Group_Acct a, C_BPartner bp "
				+ "WHERE a.C_BP_Group_ID=bp.C_BP_Group_ID AND bp.C_BPartner_ID=? AND a.C_AcctSchema_ID=?";
			para_1 = getC_BPartner_ID();
		}
		else if (AcctType == ACCTTYPE_WriteOff)
		{
			sql = "SELECT WriteOff_Acct FROM C_BP_Group_Acct a, C_BPartner bp "
				+ "WHERE a.C_BP_Group_ID=bp.C_BP_Group_ID AND bp.C_BPartner_ID=? AND a.C_AcctSchema_ID=?";
			para_1 = getC_BPartner_ID();
		}

		/** Account Type - Bank Statement   */
		else if (AcctType == ACCTTYPE_BankAsset)
		{
			sql = "SELECT B_Asset_Acct FROM C_BankAccount_Acct WHERE C_BankAccount_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_BankAccount_ID();
		}
		else if (AcctType == ACCTTYPE_InterestRev)
		{
			sql = "SELECT B_InterestRev_Acct FROM C_BankAccount_Acct WHERE C_BankAccount_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_BankAccount_ID();
		}
		else if (AcctType == ACCTTYPE_InterestExp)
		{
			sql = "SELECT B_InterestExp_Acct FROM C_BankAccount_Acct WHERE C_BankAccount_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_BankAccount_ID();
		}

		/** Account Type - Cash     */
		else if (AcctType == ACCTTYPE_CashAsset)
		{
			sql = "SELECT CB_Asset_Acct FROM C_CashBook_Acct WHERE C_CashBook_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_CashBook_ID();
		}
		else if (AcctType == ACCTTYPE_CashTransfer)
		{
			sql = "SELECT CB_CashTransfer_Acct FROM C_CashBook_Acct WHERE C_CashBook_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_CashBook_ID();
		}
		else if (AcctType == ACCTTYPE_CashExpense)
		{
			sql = "SELECT CB_Expense_Acct FROM C_CashBook_Acct WHERE C_CashBook_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_CashBook_ID();
		}
		else if (AcctType == ACCTTYPE_CashReceipt)
		{
			sql = "SELECT CB_Receipt_Acct FROM C_CashBook_Acct WHERE C_CashBook_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_CashBook_ID();
		}
		else if (AcctType == ACCTTYPE_CashDifference)
		{
			sql = "SELECT CB_Differences_Acct FROM C_CashBook_Acct WHERE C_CashBook_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_CashBook_ID();
		}

		/** Inventory Accounts          */
		else if (AcctType == ACCTTYPE_InvDifferences)
		{
			sql = "SELECT W_Differences_Acct FROM M_Warehouse_Acct WHERE M_Warehouse_ID=? AND C_AcctSchema_ID=?";
			//  "SELECT W_Inventory_Acct, W_Revaluation_Acct, W_InvActualAdjust_Acct FROM M_Warehouse_Acct WHERE M_Warehouse_ID=? AND C_AcctSchema_ID=?";
			para_1 = getM_Warehouse_ID();
		}
		else if (AcctType == ACCTTYPE_NotInvoicedReceipts)
		{
			sql = "SELECT NotInvoicedReceipts_Acct FROM C_BP_Group_Acct a, C_BPartner bp "
				+ "WHERE a.C_BP_Group_ID=bp.C_BP_Group_ID AND bp.C_BPartner_ID=? AND a.C_AcctSchema_ID=?";
			para_1 = getC_BPartner_ID();
		}

		/** Project Accounts          	*/
		else if (AcctType == ACCTTYPE_ProjectAsset)
		{
			sql = "SELECT PJ_Asset_Acct FROM C_Project_Acct WHERE C_Project_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_Project_ID();
		}
		else if (AcctType == ACCTTYPE_ProjectWIP)
		{
			sql = "SELECT PJ_WIP_Acct FROM C_Project_Acct WHERE C_Project_ID=? AND C_AcctSchema_ID=?";
			para_1 = getC_Project_ID();
		}

		/** GL Accounts                 */
		else if (AcctType == ACCTTYPE_PPVOffset)
		{
			sql = "SELECT PPVOffset_Acct FROM C_AcctSchema_GL WHERE C_AcctSchema_ID=?";
			para_1 = -1;
		}
		else if (AcctType == ACCTTYPE_CommitmentOffset)
		{
			sql = "SELECT CommitmentOffset_Acct FROM C_AcctSchema_GL WHERE C_AcctSchema_ID=?";
			para_1 = -1;
		}
		else if (AcctType == ACCTTYPE_CommitmentOffsetSales)
		{
			sql = "SELECT CommitmentOffsetSales_Acct FROM C_AcctSchema_GL WHERE C_AcctSchema_ID=?";
			para_1 = -1;
		}

		else
		{
			log.warning("Not found AcctType=" + AcctType);
			return 0;
		}
		//  Do we have sql & Parameter
		if (sql == null || para_1 == 0)
		{
			log.warning("No Parameter for AcctType=" + AcctType + " - SQL=" + sql);
			return 0;
		}

		//  Get Acct
		int Account_ID = 0;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try
		{
			pstmt = DB.prepareStatement(sql, getTrxName());
			if (para_1 == -1)   //  GL Accounts
				pstmt.setInt (1, as.getC_AcctSchema_ID());
			else
			{
				pstmt.setInt (1, para_1);
				pstmt.setInt (2, as.getC_AcctSchema_ID());
			}
			rs = pstmt.executeQuery();
			if (rs.next())
				Account_ID = rs.getInt(1);
		}
		catch (SQLException e)
		{
			log.log(Level.SEVERE, "AcctType=" + AcctType + " - SQL=" + sql, e);
			return 0;
		}
		finally {
			DB.close(rs, pstmt);
			rs = null; pstmt = null;
		}
		//	No account
		if (Account_ID == 0)
		{
			log.warning("NO account Type="
				+ AcctType + ", Record=" + p_po.get_ID());
			return 0;
		}
		return Account_ID;
	}	//	getAccount_ID

	/**
	 *	Get account record by accounting schema and account type
	 *  @param AcctType see ACCTTYPE_*
	 *  @param as accounting schema
	 *  @return MAccount
	 */
	public final MAccount getAccount (int AcctType, MAcctSchema as)
	{
		int C_ValidCombination_ID = getValidCombination_ID(AcctType, as);
		if (C_ValidCombination_ID == 0)
			return null;
		//	Return Account
		MAccount acct = MAccount.get (as.getCtx(), C_ValidCombination_ID);
		return acct;
	}	//	getAccount

	/**
	 *  Get DocLine with ID
	 *  @param Record_ID Record ID
	 *  @return DocLine
	 */
	public DocLine getDocLine (int Record_ID)
	{
		if (p_lines == null || p_lines.length == 0 || Record_ID == 0)
			return null;

		for (int i = 0; i < p_lines.length; i++)
		{
			if (p_lines[i].get_ID() == Record_ID)
				return p_lines[i];
		}
		return null;
	}   //  getDocLine

	/**
	 *  String Representation
	 *  @return String
	 */
	public String toString()
	{
		return p_po.toString();
	}   //  toString

	/**
	 * 	Get AD_Client_ID
	 *	@return AD_Client_ID
	 */
	public int getAD_Client_ID()
	{
		return p_po.getAD_Client_ID();
	}	//	getAD_Client_ID

	/**
	 * 	Get AD_Org_ID
	 *	@return AD_Org_ID
	 */
	public int getAD_Org_ID()
	{
		return p_po.getAD_Org_ID();
	}	//	getAD_Org_ID

	/**
	 * 	Get Document No
	 *	@return document No
	 */
	public String getDocumentNo()
	{
		if (m_DocumentNo != null)
			return m_DocumentNo;
		int index = p_po.get_ColumnIndex("DocumentNo");
		if (index == -1)
			index = p_po.get_ColumnIndex("Name");
		if (index == -1)
			throw new UnsupportedOperationException("No DocumentNo");
		m_DocumentNo = (String)p_po.get_Value(index);
		return m_DocumentNo;
	}	//	getDocumentNo

	/**
	 * 	Get Description
	 *	@return Description
	 */
	public String getDescription()
	{
		if (m_Description == null)
		{
			int index = p_po.get_ColumnIndex("Description");
			if (index != -1)
				m_Description = (String)p_po.get_Value(index);
			else
				m_Description = "";
		}
		return m_Description;
	}	//	getDescription

	/**
	 * 	Get C_Currency_ID
	 *	@return C_Currency_ID
	 */
	public int getC_Currency_ID()
	{
		if (m_C_Currency_ID == -1)
		{
			int index = p_po.get_ColumnIndex("C_Currency_ID");
			if (index != -1)
			{
				Integer ii = (Integer)p_po.get_Value(index);
				if (ii != null)
					m_C_Currency_ID = ii.intValue();
			}
			if (m_C_Currency_ID == -1)
				m_C_Currency_ID = NO_CURRENCY;
		}
		return m_C_Currency_ID;
	}	//	getC_Currency_ID

	/**
	 * 	Set C_Currency_ID
	 *	@param C_Currency_ID id
	 */
	public void setC_Currency_ID (int C_Currency_ID)
	{
		m_C_Currency_ID = C_Currency_ID;
	}	//	setC_Currency_ID

	/**
	 * 	Is Multi Currency
	 *	@return mc
	 */
	public boolean isMultiCurrency()
	{
		return m_MultiCurrency;
	}	//	isMultiCurrency

	/**
	 * 	Set Multi Currency
	 *	@param mc multi currency
	 */
	public void setIsMultiCurrency (boolean mc)
	{
		m_MultiCurrency = mc;
	}	//	setIsMultiCurrency

	/**
	 * 	Is Tax Included
	 *	@return tax incl
	 */
	public boolean isTaxIncluded()
	{
		return m_TaxIncluded;
	}	//	isTaxIncluded

	/**
	 * 	Set Tax Included
	 *	@param ti Tax Included
	 */
	public void setIsTaxIncluded (boolean ti)
	{
		m_TaxIncluded = ti;
	}	//	setIsTaxIncluded

	/**
	 * 	Get C_ConversionType_ID
	 *	@return C_ConversionType_ID
	 */
	public int getC_ConversionType_ID()
	{
		int index = p_po.get_ColumnIndex("C_ConversionType_ID");
		if (index != -1)
		{
			Integer ii = (Integer)p_po.get_Value(index);
			if (ii != null)
				return ii.intValue();
		}
		return 0;
	}	//	getC_ConversionType_ID

	/**
	 * @return currency rate or null
	 */
	public BigDecimal getCurrencyRate()
	{		
		return null;
	}

	/**
	 * 	Get GL_Category_ID
	 *	@return GL_Category_ID
	 */
	public int getGL_Category_ID()
	{
		int index = p_po.get_ColumnIndex("GL_Category_ID");
		if (index != -1)
		{
			Integer ii = (Integer)p_po.get_Value(index);
			if (ii != null)
				return ii.intValue();
		}
		return m_GL_Category_ID;
	}	//	getGL_Category_ID

	/**
	 * 	Get getGL_Budget_ID
	 *	@return GL_Budget_ID or 0
	 */
	public int getGL_Budget_ID()
	{
		int index = p_po.get_ColumnIndex("GL_Budget_ID");
		if (index != -1)
		{
			Integer ii = (Integer)p_po.get_Value(index);
			if (ii != null)
				return ii.intValue();
		}
		return 0;
	}	//	getGL_Budget_ID

	/**
	 * 	Get Accounting Date
	 *	@return DateAcct or null
	 */
	public Timestamp getDateAcct()
	{
		if (m_DateAcct != null)
			return m_DateAcct;
		int index = p_po.get_ColumnIndex("DateAcct");
		if (index != -1)
		{
			m_DateAcct = (Timestamp)p_po.get_Value(index);
			if (m_DateAcct != null)
				return m_DateAcct;
		}
		throw new IllegalStateException("No DateAcct");
	}	//	getDateAcct

	/**
	 * 	Set Date Acct
	 *	@param da accounting date
	 */
	public void setDateAcct (Timestamp da)
	{
		m_DateAcct = da;
	}	//	setDateAcct

	/**
	 * 	Get Document Date
	 *	@return document date
	 */
	public Timestamp getDateDoc()
	{
		if (m_DateDoc != null)
			return m_DateDoc;
		int index = p_po.get_ColumnIndex("DateDoc");
		if (index == -1)
			index = p_po.get_ColumnIndex("MovementDate");
		if (index != -1)
		{
			m_DateDoc = (Timestamp)p_po.get_Value(index);
			if (m_DateDoc != null)
				return m_DateDoc;
		}
		throw new IllegalStateException("No DateDoc");
	}	//	getDateDoc

	/**
	 * 	Set Date Doc
	 *	@param dd document date
	 */
	public void setDateDoc (Timestamp dd)
	{
		m_DateDoc = dd;
	}	//	setDateDoc

	/**
	 * 	Is Document Posted
	 *	@return true if posted
	 */
	public boolean isPosted()
	{
		int index = p_po.get_ColumnIndex("Posted");
		if (index != -1)
		{
			Object posted = p_po.get_Value(index);
			if (posted instanceof Boolean)
				return ((Boolean)posted).booleanValue();
			if (posted instanceof String)
				return "Y".equals(posted);
		}
		throw new IllegalStateException("No Posted");
	}	//	isPosted

	/**
	 * 	Is Sales Trx
	 *	@return true if it is sales trx
	 */
	public boolean isSOTrx()
	{
		int index = p_po.get_ColumnIndex("IsSOTrx");
		if (index == -1)
			index = p_po.get_ColumnIndex("IsReceipt");
		if (index != -1)
		{
			Object posted = p_po.get_Value(index);
			if (posted instanceof Boolean)
				return ((Boolean)posted).booleanValue();
			if (posted instanceof String)
				return "Y".equals(posted);
		}
		return false;
	}	//	isSOTrx

	/**
	 * 	Get C_DocType_ID
	 *	@return C_DocType_ID or 0
	 */
	public int getC_DocType_ID()
	{
		int index = p_po.get_ColumnIndex("C_DocType_ID");
		if (index != -1)
		{
			Integer ii = (Integer)p_po.get_Value(index);
			//	DocType does not exist - get DocTypeTarget
			if (ii != null && ii.intValue() == 0)
			{
				index = p_po.get_ColumnIndex("C_DocTypeTarget_ID");
				if (index != -1)
					ii = (Integer)p_po.get_Value(index);
			}
			if (ii != null)
				return ii.intValue();
		}
		else
		{
			if (p_po.get_TableName().equals(I_M_MatchPO.Table_Name)) 
			{				
				int docTypeId = DB.getSQLValue((String)null, DOC_TYPE_BY_DOC_BASE_TYPE_SQL, 
						p_po.getAD_Client_ID(), Doc.DOCTYPE_MatMatchPO);
				if (docTypeId > 0)
					return docTypeId;
			}
			else if (p_po.get_TableName().equals(I_M_MatchInv.Table_Name)) 
			{				
				int docTypeId = DB.getSQLValue((String)null, DOC_TYPE_BY_DOC_BASE_TYPE_SQL, 
						p_po.getAD_Client_ID(), Doc.DOCTYPE_MatMatchInv);
				if (docTypeId > 0)
					return docTypeId;
			}
			else if (p_po.get_TableName().equals(I_C_AllocationHdr.Table_Name)) 
			{				
				int docTypeId = DB.getSQLValue((String)null, DOC_TYPE_BY_DOC_BASE_TYPE_SQL, 
						p_po.getAD_Client_ID(), Doc.DOCTYPE_Allocation);
				if (docTypeId > 0)
					return docTypeId;
			}
			else if (p_po.get_TableName().equals(I_C_Cash.Table_Name)) 
			{				
				int docTypeId = DB.getSQLValue((String)null, DOC_TYPE_BY_DOC_BASE_TYPE_SQL, 
						p_po.getAD_Client_ID(), Doc.DOCTYPE_CashJournal);
				if (docTypeId > 0)
					return docTypeId;
			}
			else if (p_po.get_TableName().equals(I_C_ProjectIssue.Table_Name)) 
			{				
				int docTypeId = DB.getSQLValue((String)null, DOC_TYPE_BY_DOC_BASE_TYPE_SQL, 
						p_po.getAD_Client_ID(), Doc.DOCTYPE_ProjectIssue);
				if (docTypeId > 0)
					return docTypeId;
			}
		}
		return 0;
	}	//	getC_DocType_ID

	/**
	 * 	Get header level C_Charge_ID
	 *	@return C_Charge_ID or 0
	 */
	public int getC_Charge_ID()
	{
		int index = p_po.get_ColumnIndex("C_Charge_ID");
		if (index != -1)
		{
			Integer ii = (Integer)p_po.get_Value(index);
			if (ii != null)
				return ii.intValue();
		}
		return 0;
	}	//	getC_Charge_ID
	
	/**
	 * 	Get header level A_Asset_ID
	 *	@return A_Asset_ID or 0
	 */
	public int getA_Asset_ID()
	{
		if(m_A_Asset_ID == -1)
		{
			int index = p_po.get_ColumnIndex("A_Asset_ID");
			if (index != -1)
			{
				Integer ii = (Integer) p_po.get_Value(index);
				if (ii != null)
					m_A_Asset_ID = ii.intValue();
			}
			if (m_A_Asset_ID == -1)
				m_A_Asset_ID = 0;
		}
		return m_A_Asset_ID;
	}	//	getA_Asset_ID

	/**
	 * Set A_Asset_ID
	 * @param m_A_Asset_ID Asset
	 */
	public void setA_Asset_ID(int m_A_Asset_ID)
	{
		this.m_A_Asset_ID = m_A_Asset_ID;
	}// setA_Asset_ID

	/**
	 * 	Get SalesRep_ID
	 *	@return SalesRep_ID or 0
	 */
	public int getSalesRep_ID()
	{
		int index = p_po.get_ColumnIndex("SalesRep_ID");
		if (index != -1)
		{
			Integer ii = (Integer)p_po.get_Value(index);
			if (ii != null)
				return ii.intValue();
		}
		return 0;
	}	//	getSalesRep_ID

	/**
	 * 	Get C_BankAccount_ID
	 *	@return C_BankAccount_ID or 0
	 */
	public int getC_BankAccount_ID()
	{
		if (m_C_BankAccount_ID == -1)
		{
			int index = p_po.get_ColumnIndex("C_BankAccount_ID");
			if (index != -1)
			{
				Integer ii = (Integer)p_po.get_Value(index);
				if (ii != null)
					m_C_BankAccount_ID = ii.intValue();
			}
			if (m_C_BankAccount_ID == -1)
				m_C_BankAccount_ID = 0;
		}
		return m_C_BankAccount_ID;
	}	//	getC_BankAccount_ID

	/**
	 * 	Set C_BankAccount_ID
	 *	@param C_BankAccount_ID bank acct
	 */
	public void setC_BankAccount_ID (int C_BankAccount_ID)
	{
		m_C_BankAccount_ID = C_BankAccount_ID;
	}	//	setC_BankAccount_ID

	/**
	 * 	Get C_CashBook_ID
	 *	@return C_CashBook_ID or 0
	 */
	public int getC_CashBook_ID()
	{
		if (m_C_CashBook_ID == -1)
		{
			int index = p_po.get_ColumnIndex("C_CashBook_ID");
			if (index != -1)
			{
				Integer ii = (Integer)p_po.get_Value(index);
				if (ii != null)
					m_C_CashBook_ID = ii.intValue();
			}
			if (m_C_CashBook_ID == -1)
				m_C_CashBook_ID = 0;
		}
		return m_C_CashBook_ID;
	}	//	getC_CashBook_ID

	/**
	 * 	Set C_CashBook_ID
	 *	@param C_CashBook_ID cash book
	 */
	public void setC_CashBook_ID (int C_CashBook_ID)
	{
		m_C_CashBook_ID = C_CashBook_ID;
	}	//	setC_CashBook_ID

	/**
	 * 	Get M_Warehouse_ID
	 *	@return M_Warehouse_ID or 0
	 */
	public int getM_Warehouse_ID()
	{
		int index = p_po.get_ColumnIndex("M_Warehouse_ID");
		if (index != -1)
		{
			Integer ii = (Integer)p_po.get_Value(index);
			if (ii != null)
				return ii.intValue();
		}
		return 0;
	}	//	getM_Warehouse_ID
	
	/**
	 * 	Get M_WarehouseTo_ID
	 *	@return M_WarehouseTo_ID or 0
	 */
	public int getM_WarehouseTo_ID()
	{
		int index = p_po.get_ColumnIndex("M_WarehouseTo_ID");
		if (index != -1)
		{
			Integer ii = (Integer)p_po.get_Value(index);
			if (ii != null)
				return ii.intValue();
		}
		return 0;
	}	//	getM_WarehouseTo_ID

	/**
	 * Get C_CostCenter_ID
	 * 
	 * @return C_CostCenter_ID or 0
	 */
	public int getC_CostCenter_ID()
	{
		if (m_C_CostCenter_ID == -1)
		{
			int index = p_po.get_ColumnIndex("C_CostCenter_ID");
			if (index != -1)
			{
				Integer ii = (Integer) p_po.get_Value(index);
				if (ii != null)
					m_C_CostCenter_ID = ii.intValue();
			}
			if (m_C_CostCenter_ID == -1)
				m_C_CostCenter_ID = 0;
		}
		return m_C_CostCenter_ID;
	}// getC_CostCenter_ID
	
	/**
	 * Set C_CostCenter_ID
	 * 
	 * @param m_C_CostCenter_ID Cost Center
	 */
	public void setC_CostCenter_ID(int m_C_CostCenter_ID)
	{
		this.m_C_CostCenter_ID = m_C_CostCenter_ID;
	}// setC_CostCenter_ID

	/**
	 * Get C_Department_ID
	 * 
	 * @return C_Department_ID or 0
	 */
	public int getC_Department_ID()
	{
		if (m_C_Department_ID == -1)
		{
			int index = p_po.get_ColumnIndex("C_Department_ID");
			if (index != -1)
			{
				Integer ii = (Integer) p_po.get_Value(index);
				if (ii != null)
					m_C_Department_ID = ii.intValue();
			}
			if (m_C_Department_ID == -1)
				m_C_Department_ID = 0;
		}
		return m_C_Department_ID;
	}// getC_Department_ID
	
	/**
	 *  ASI
	 *  @return M_AttributeSetInstance_ID
	 */
	public int getM_AttributeSetInstance_ID()
	{
		int index = p_po.get_ColumnIndex("M_AttributeSetInstance_ID");
		if (index != -1)
		{
			Integer ii = (Integer)p_po.get_Value(index);
			if (ii != null)
				return ii.intValue();
		}
		return 0;
	}   //  getM_AttributeSetInstance_ID
	
	/**
	 * Set C_Department_ID
	 * 
	 * @param m_C_Department_ID Department
	 */
	public void setC_Department_ID(int m_C_Department_ID)
	{
		this.m_C_Department_ID = m_C_Department_ID;
	} // setC_Department_ID

	/**
	 * 	Get C_BPartner_ID
	 *	@return C_BPartner_ID or 0
	 */
	public int getC_BPartner_ID()
	{
		if (m_C_BPartner_ID == -1)
		{
			int index = p_po.get_ColumnIndex("C_BPartner_ID");
			if (index != -1)
			{
				Integer ii = (Integer)p_po.get_Value(index);
				if (ii != null)
					m_C_BPartner_ID = ii.intValue();
			}
			if (m_C_BPartner_ID == -1)
				m_C_BPartner_ID = 0;
		}
		return m_C_BPartner_ID;
	}	//	getC_BPartner_ID

	/**
	 * 	Set C_BPartner_ID
	 *	@param C_BPartner_ID bp
	 */
	public void setC_BPartner_ID (int C_BPartner_ID)
	{
		m_C_BPartner_ID = C_BPartner_ID;
	}	//	setC_BPartner_ID
	
	/**
	 *  Get BPartner Employee
	 *  @return C_Employee_ID
	 */
	public int getC_Employee_ID()
	{
		if (m_C_Employee_ID == -1)
		{
			int index = p_po.get_ColumnIndex("C_Employee_ID");
			if (index != -1)
			{
				Integer ii = (Integer) p_po.get_Value(index);
				if (ii != null)
					m_C_Employee_ID = ii.intValue();
			}
			if (m_C_Employee_ID == -1)
				m_C_Employee_ID = 0;
		}
		return m_C_Employee_ID;
	}// getC_Employee_ID

	/**
	 * Set C_Employee_ID
	 * 
	 * @param C_Employee_ID bp
	 */
	public void setC_Employee_ID(int C_Employee_ID) {
		m_C_Employee_ID = C_Employee_ID;
	} // setC_Employee_ID

	/**
	 * 	Get C_BPartner_Location_ID
	 *	@return C_BPartner_Location_ID or 0
	 */
	public int getC_BPartner_Location_ID()
	{
		int index = p_po.get_ColumnIndex("C_BPartner_Location_ID");
		if (index != -1)
		{
			Integer ii = (Integer)p_po.get_Value(index);
			if (ii != null)
				return ii.intValue();
		}
		return 0;
	}	//	getC_BPartner_Location_ID

	/**
	 * 	Get C_Project_ID
	 *	@return C_Project_ID or 0
	 */
	public int getC_Project_ID()
	{
		int index = p_po.get_ColumnIndex("C_Project_ID");
		if (index != -1)
		{
			Integer ii = (Integer)p_po.get_Value(index);
			if (ii != null)
				return ii.intValue();
		}
		return 0;
	}	//	getC_Project_ID

	/**
	 * 	Get C_ProjectPhase_ID
	 *	@return C_ProjectPhase_ID or 0
	 */
	public int getC_ProjectPhase_ID()
	{
		int index = p_po.get_ColumnIndex("C_ProjectPhase_ID");
		if (index != -1)
		{
			Integer ii = (Integer)p_po.get_Value(index);
			if (ii != null)
				return ii.intValue();
		}
		return 0;
	}	//	getC_ProjectPhase_ID

	/**
	 * 	Get C_ProjectTask_ID
	 *	@return C_ProjectTask_ID or 0
	 */
	public int getC_ProjectTask_ID()
	{
		int index = p_po.get_ColumnIndex("C_ProjectTask_ID");
		if (index != -1)
		{
			Integer ii = (Integer)p_po.get_Value(index);
			if (ii != null)
				return ii.intValue();
		}
		return 0;
	}	//	getC_ProjectTask_ID

	/**
	 * 	Get C_SalesRegion_ID
	 *	@return C_SalesRegion_ID or 0
	 */
	public int getC_SalesRegion_ID()
	{
		int index = p_po.get_ColumnIndex("C_SalesRegion_ID");
		if (index != -1)
		{
			Integer ii = (Integer)p_po.get_Value(index);
			if (ii != null)
				return ii.intValue();
		}
		return 0;
	}	//	getC_SalesRegion_ID

	/**
	 * 	Get C_SalesRegion_ID
	 *	@return C_SalesRegion_ID or 0
	 */
	public int getBP_C_SalesRegion_ID()
	{
		if (m_BP_C_SalesRegion_ID == -1)
		{
			int index = p_po.get_ColumnIndex("C_SalesRegion_ID");
			if (index != -1)
			{
				Integer ii = (Integer)p_po.get_Value(index);
				if (ii != null)
					m_BP_C_SalesRegion_ID = ii.intValue();
			}
			if (m_BP_C_SalesRegion_ID == -1)
				m_BP_C_SalesRegion_ID = 0;
		}
		return m_BP_C_SalesRegion_ID;
	}	//	getBP_C_SalesRegion_ID

	/**
	 * 	Set C_SalesRegion_ID
	 *	@param C_SalesRegion_ID id
	 */
	public void setBP_C_SalesRegion_ID (int C_SalesRegion_ID)
	{
		m_BP_C_SalesRegion_ID = C_SalesRegion_ID;
	}	//	setBP_C_SalesRegion_ID

	/**
	 * 	Get C_Activity_ID
	 *	@return C_Activity_ID or 0
	 */
	public int getC_Activity_ID()
	{
		int index = p_po.get_ColumnIndex("C_Activity_ID");
		if (index != -1)
		{
			Integer ii = (Integer)p_po.get_Value(index);
			if (ii != null)
				return ii.intValue();
		}
		return 0;
	}	//	getC_Activity_ID

	/**
	 * 	Get C_Campaign_ID
	 *	@return C_Campaign_ID or 0
	 */
	public int getC_Campaign_ID()
	{
		int index = p_po.get_ColumnIndex("C_Campaign_ID");
		if (index != -1)
		{
			Integer ii = (Integer)p_po.get_Value(index);
			if (ii != null)
				return ii.intValue();
		}
		return 0;
	}	//	getC_Campaign_ID

	/**
	 * 	Get M_Product_ID
	 *	@return M_Product_ID or 0
	 */
	public int getM_Product_ID()
	{
		int index = p_po.get_ColumnIndex("M_Product_ID");
		if (index != -1)
		{
			Integer ii = (Integer)p_po.get_Value(index);
			if (ii != null)
				return ii.intValue();
		}
		return 0;
	}	//	getM_Product_ID

	/**
	 * 	Get AD_OrgTrx_ID
	 *	@return AD_OrgTrx_ID or 0
	 */
	public int getAD_OrgTrx_ID()
	{
		int index = p_po.get_ColumnIndex("AD_OrgTrx_ID");
		if (index != -1)
		{
			Integer ii = (Integer)p_po.get_Value(index);
			if (ii != null)
				return ii.intValue();
		}
		return 0;
	}	//	getAD_OrgTrx_ID

	/**
	 * 	Get C_LocFrom_ID
	 *	@return from C_Location_ID or 0
	 */
	public int getC_LocFrom_ID()
	{
		return m_C_LocFrom_ID;
	}	//	getC_LocFrom_ID

	/**
	 * 	Set C_LocFrom_ID
	 *	@param C_LocFrom_ID loc from
	 */
	public void setC_LocFrom_ID(int C_LocFrom_ID)
	{
		m_C_LocFrom_ID = C_LocFrom_ID;
	}	//	setC_LocFrom_ID

	/**
	 * 	Get C_LocTo_ID
	 *	@return to C_Location_ID or 0
	 */
	public int getC_LocTo_ID()
	{
		return m_C_LocTo_ID;
	}	//	getC_LocTo_ID

	/**
	 * 	Set C_LocTo_ID
	 *	@param C_LocTo_ID loc to
	 */
	public void setC_LocTo_ID(int C_LocTo_ID)
	{
		m_C_LocTo_ID = C_LocTo_ID;
	}	//	setC_LocTo_ID

	/**
	 * 	Get User1_ID
	 *	@return User1_ID or 0
	 */
	public int getUser1_ID()
	{
		int index = p_po.get_ColumnIndex("User1_ID");
		if (index != -1)
		{
			Integer ii = (Integer)p_po.get_Value(index);
			if (ii != null)
				return ii.intValue();
		}
		return 0;
	}	//	getUser1_ID

	/**
	 * 	Get User2_ID
	 *	@return User2_ID or 0
	 */
	public int getUser2_ID()
	{
		int index = p_po.get_ColumnIndex("User2_ID");
		if (index != -1)
		{
			Integer ii = (Integer)p_po.get_Value(index);
			if (ii != null)
				return ii.intValue();
		}
		return 0;
	}	//	getUser2_ID

    /**
	 * Get value by column name
	 * @param ColumnName
	 * @return column value or 0 (if column doesn't exists)
	 */
	public int getValue (String ColumnName)
	{
		int index = p_po.get_ColumnIndex(ColumnName);
		if (index != -1)
		{
			Integer ii = (Integer)p_po.get_Value(index);
			if (ii != null)
				return ii.intValue();
		}
		return 0;
	}	//	getValue

	 /**
	 * Get value by column name
	 * @param ColumnName
	 * @return column value or null (if column doesn't exists)
	 */
	public String get_ValueAsString (String ColumnName)
	{
		int index = p_po.get_ColumnIndex(ColumnName);
		if (index != -1)
		{
			return p_po.get_ValueAsString(index);
		}
		return null;
	}	//	get_ValueAsString

	/**
	 *  Load Document Details
	 *  @return error message or null
	 */
	protected abstract String loadDocumentDetails ();

	/**
	 *  Get Source Currency Balance - subtracts line (and tax) amounts from total - no rounding
	 *  @return positive amount, if total header is bigger than lines
	 */
	public abstract BigDecimal getBalance();

	/**
	 *  Create Facts (the accounting logic)
	 *  @param as accounting schema
	 *  @return Facts
	 */
	public abstract ArrayList<Fact> createFacts (MAcctSchema as);

	/**
	 *  Get Facts (the accounting logic)
	 *  @return Facts
	 */
	public ArrayList<Fact> getFacts() {
		return m_fact;
	}
	
	/**
	 * Get accounting schema
	 * @return MAcctSchema
	 */
	protected MAcctSchema getAcctSchema() {
		return m_as;
	}
	
	/**
	 * Is posting of document should be deferred to next run of accounting posting
	 * @return true if posting of document should be deferred to next run of accounting posting
	 */
	public boolean isDeferPosting() {
		return false;
	}
	
	/** In a Back-Date Posting Process **/
	private boolean isInBackDatePostingProcess;
	
	/**
	 * Is in a back-date posting process?
	 * @return true if is in a back-date posting process
	 */
	public boolean isInBackDatePostingProcess() {
		return isInBackDatePostingProcess;
	}
}   //  Doc
