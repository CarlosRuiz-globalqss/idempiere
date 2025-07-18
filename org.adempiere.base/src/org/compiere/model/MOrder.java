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

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;

import org.adempiere.base.Core;
import org.adempiere.base.CreditStatus;
import org.adempiere.base.ICreditManager;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.exceptions.BPartnerNoBillToAddressException;
import org.adempiere.exceptions.BPartnerNoShipToAddressException;
import org.adempiere.exceptions.DBException;
import org.adempiere.exceptions.FillMandatoryException;
import org.adempiere.model.ITaxProvider;
import org.adempiere.process.SalesOrderRateInquiryProcess;
import org.adempiere.util.IReservationTracer;
import org.adempiere.util.IReservationTracerFactory;
import org.compiere.print.ReportEngine;
import org.compiere.process.DocAction;
import org.compiere.process.DocumentEngine;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Msg;
import org.compiere.util.TimeUtil;
import org.compiere.util.Util;
import org.eevolution.model.MPPProductBOM;
import org.eevolution.model.MPPProductBOMLine;

/**
 *  Order Model.
 *
 *  @author Jorg Janke
 *
 *  @author victor.perez@e-evolution.com, e-Evolution http://www.e-evolution.com
 * 			<li> FR [ 2520591 ] Support multiples calendar for Org 
 *			@see https://sourceforge.net/p/adempiere/feature-requests/631/
 *  @version $Id: MOrder.java,v 1.5 2006/10/06 00:42:24 jjanke Exp $
 * 
 *  @author Teo Sarca, www.arhipac.ro
 * 			<li>BF [ 2419978 ] Voiding PO, requisition don't set on NULL
 * 			<li>BF [ 2892578 ] Order should autoset only active price lists
 * 				https://sourceforge.net/p/adempiere/feature-requests/873/
 *  @author Michael Judd, www.akunagroup.com
 *          <li>BF [ 2804888 ] Incorrect reservation of products with attributes
 */
public class MOrder extends X_C_Order implements DocAction
{
	/**
	 * generated serial id
	 */
	private static final long serialVersionUID = 9095740800513665542L;

	/** Matching SELECT SQL template */
	private static final String BASE_MATCHING_SQL =
			"""
				SELECT hdr.C_Order_ID, hdr.DocumentNo, hdr.DateOrdered, bp.Name, hdr.C_BPartner_ID,
				lin.Line, lin.C_OrderLine_ID, p.Name, lin.M_Product_ID,
				lin.QtyOrdered,
				%s,
				org.Name, hdr.AD_Org_ID 
				 FROM C_Order hdr 
				 INNER JOIN AD_Org org ON (hdr.AD_Org_ID=org.AD_Org_ID)
				 INNER JOIN C_BPartner bp ON (hdr.C_BPartner_ID=bp.C_BPartner_ID)
				 INNER JOIN C_OrderLine lin ON (hdr.C_Order_ID=lin.C_Order_ID)
				 INNER JOIN M_Product p ON (lin.M_Product_ID=p.M_Product_ID)
				 INNER JOIN C_DocType dt ON (hdr.C_DocType_ID=dt.C_DocType_ID AND dt.DocBaseType='POO')
				 FULL JOIN M_MatchPO mo ON (lin.C_OrderLine_ID=mo.C_OrderLine_ID)  
				 WHERE %s
				 AND hdr.DocStatus IN ('CO','CL')
			""";
	
	/** Matching GROUP BY template */
	private static final String BASE_MATCHING_GROUP_BY_SQL =
			"""
				GROUP BY hdr.C_Order_ID,hdr.DocumentNo,hdr.DateOrdered,bp.Name,hdr.C_BPartner_ID,
				lin.Line,lin.C_OrderLine_ID,p.Name,lin.M_Product_ID,lin.QtyOrdered, org.Name, hdr.AD_Org_ID 
				HAVING %s <> %s
			""";
	
	public static final String NOT_FULLY_MATCHED_TO_RECEIPT = BASE_MATCHING_SQL
			.formatted("SUM(CASE WHEN (mo.M_InOutLine_ID IS NOT NULL) THEN COALESCE(mo.Qty,0) ELSE 0 END)",
					" ( mo.M_InOutLine_ID IS NULL OR "
							+ " (lin.QtyOrdered <>  (SELECT sum(mo1.Qty) AS Qty" 
							+ " FROM m_matchpo mo1 WHERE "
							+ " mo1.C_ORDERLINE_ID=lin.C_ORDERLINE_ID AND "
							+ " hdr.C_ORDER_ID=lin.C_ORDER_ID AND "
							+ " mo1.M_InOutLine_ID"
							+ " IS NOT NULL group by mo1.C_ORDERLINE_ID))) ");
	
	public static final String NOT_FULLY_MATCHED_TO_RECEIPT_GROUP_BY = BASE_MATCHING_GROUP_BY_SQL
			.formatted("lin.QtyOrdered", "SUM(CASE WHEN (mo.M_InOutLine_ID IS NOT NULL) THEN COALESCE(mo.Qty,0) ELSE 0 END) ");
	
	public static final String FULL_OR_PARTIALLY_MATCHED_TO_RECEIPT = BASE_MATCHING_SQL
			.formatted("SUM(CASE WHEN (mo.M_InOutLine_ID IS NOT NULL) THEN COALESCE(mo.Qty,0) ELSE 0 END)", " mo.M_InOutLine_ID IS NOT NULL ");
	
	public static final String FULL_OR_PARTIALLY_MATCHED_TO_RECEIPT_GROUP_BY = BASE_MATCHING_GROUP_BY_SQL
			.formatted("0", "SUM(CASE WHEN (mo.M_InOutLine_ID IS NOT NULL) THEN COALESCE(mo.Qty,0) ELSE 0 END) ");
	
	public static final String NOT_FULLY_MATCHED_TO_INVOICE = BASE_MATCHING_SQL
			.formatted("SUM(CASE WHEN (mo.C_InvoiceLine_ID IS NOT NULL) THEN COALESCE(mo.Qty,0) ELSE 0 END)",
					" ( mo.C_InvoiceLine_ID IS NULL OR "
							+ " (lin.QtyOrdered <>  (SELECT sum(mo1.Qty) AS Qty" 
							+ " FROM m_matchpo mo1 WHERE "
							+ " mo1.C_ORDERLINE_ID=lin.C_ORDERLINE_ID AND "
							+ " hdr.C_ORDER_ID=lin.C_ORDER_ID AND "
							+ " mo1.C_InvoiceLine_ID"
							+ " IS NOT NULL group by mo1.C_ORDERLINE_ID))) ");
	
	public static final String NOT_FULLY_MATCHED_TO_INVOICE_GROUP_BY = BASE_MATCHING_GROUP_BY_SQL
			.formatted("lin.QtyOrdered", "SUM(CASE WHEN (mo.C_InvoiceLine_ID IS NOT NULL) THEN COALESCE(mo.Qty,0) ELSE 0 END) ");
			
	public static final String FULL_OR_PARTIALLY_MATCHED_TO_INVOICE = BASE_MATCHING_SQL
			.formatted("SUM(CASE WHEN (mo.C_InvoiceLine_ID IS NOT NULL) THEN COALESCE(mo.Qty,0) ELSE 0 END)", " mo.C_InvoiceLine_ID IS NOT NULL ");
	
	public static final String FULL_OR_PARTIALLY_MATCHED_TO_INVOICE_GROUP_BY = BASE_MATCHING_GROUP_BY_SQL
			.formatted("0", "SUM(CASE WHEN (mo.C_InvoiceLine_ID IS NOT NULL) THEN COALESCE(mo.Qty,0) ELSE 0 END) ");
	
	/**
	 * @param C_BPartner_ID
	 * @param M_Product_ID
	 * @param M_InOutLine_ID
	 * @param from
	 * @param to
	 * @param trxName
	 * @return list of orders not fully matched to receipt
	 */
	public static List<MatchingRecord> getNotFullyMatchedToReceipt(int C_BPartner_ID, int M_Product_ID, int M_InOutLine_ID, Timestamp from, Timestamp to, String trxName) {
		StringBuilder builder = new StringBuilder(NOT_FULLY_MATCHED_TO_RECEIPT);
		if (M_InOutLine_ID > 0) {
			builder.append(" AND mo.M_InOutLine_ID = ").append(M_InOutLine_ID);
		}
		if (M_Product_ID > 0) {
			builder.append(" AND lin.M_Product_ID=").append(M_Product_ID);
		}
		if (C_BPartner_ID > 0) {
			builder.append(" AND hdr.C_BPartner_ID=").append(C_BPartner_ID);
		}
		if (from != null) {
			builder.append(" AND ").append("hdr.DateOrdered").append(" >= ").append(DB.TO_DATE(from));
		}
		if (to != null) {
			builder.append(" AND ").append("hdr.DateOrdered").append(" <= ").append(DB.TO_DATE(to));
		}
		String sql = MRole.getDefault().addAccessSQL(
				builder.toString(), "hdr", MRole.SQL_FULLYQUALIFIED, MRole.SQL_RO)
				+ NOT_FULLY_MATCHED_TO_RECEIPT_GROUP_BY;
		
		List<MatchingRecord> records = new ArrayList<>();
		try (PreparedStatement stmt = DB.prepareStatement(sql, trxName)) {
			ResultSet rs = stmt.executeQuery();
			while (rs.next()) {
				MatchingRecord matchingRecord = new MatchingRecord(rs.getInt(1), rs.getString(2), rs.getTimestamp(3), rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), 
						rs.getString(8), rs.getInt(9), rs.getBigDecimal(10), rs.getBigDecimal(11), rs.getString(12), rs.getInt(13));
				records.add(matchingRecord);
			}
		} catch (SQLException e) {
			throw new DBException(e.getMessage(), e);
		}
		return records;
	}
	
	/**
	 * @param C_BPartner_ID
	 * @param M_Product_ID
	 * @param M_InOutLine_ID
	 * @param from
	 * @param to
	 * @param trxName
	 * @return list of orders full or partially match to receipt 
	 */
	public static List<MatchingRecord> getFullOrPartiallyMatchedToReceipt(int C_BPartner_ID, int M_Product_ID, int M_InOutLine_ID, Timestamp from, Timestamp to, String trxName) {
		StringBuilder builder = new StringBuilder(FULL_OR_PARTIALLY_MATCHED_TO_RECEIPT);
		if (M_InOutLine_ID > 0) {
			builder.append(" AND mo.M_InOutLine_ID = ").append(M_InOutLine_ID);
		}
		if (M_Product_ID > 0) {
			builder.append(" AND lin.M_Product_ID=").append(M_Product_ID);
		}
		if (C_BPartner_ID > 0) {
			builder.append(" AND hdr.C_BPartner_ID=").append(C_BPartner_ID);
		}
		if (from != null) {
			builder.append(" AND ").append("hdr.DateOrdered").append(" >= ").append(DB.TO_DATE(from));
		}
		if (to != null) {
			builder.append(" AND ").append("hdr.DateOrdered").append(" <= ").append(DB.TO_DATE(to));
		}
		String sql = MRole.getDefault().addAccessSQL(
				builder.toString(), "hdr", MRole.SQL_FULLYQUALIFIED, MRole.SQL_RO)
				+ FULL_OR_PARTIALLY_MATCHED_TO_RECEIPT_GROUP_BY;
		
		List<MatchingRecord> records = new ArrayList<>();
		try (PreparedStatement stmt = DB.prepareStatement(sql, trxName)) {
			ResultSet rs = stmt.executeQuery();
			while (rs.next()) {
				MatchingRecord matchingRecord = new MatchingRecord(rs.getInt(1), rs.getString(2), rs.getTimestamp(3), rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), 
						rs.getString(8), rs.getInt(9), rs.getBigDecimal(10), rs.getBigDecimal(11), rs.getString(12), rs.getInt(13));
				records.add(matchingRecord);
			}
		} catch (SQLException e) {
			throw new DBException(e.getMessage(), e);
		}
		return records;
	}
	
	/**
	 * @param C_BPartner_ID
	 * @param M_Product_ID
	 * @param C_InvoiceLine_ID
	 * @param from
	 * @param to
	 * @param trxName
	 * @return list of orders not fully matched to invoice
	 */
	public static List<MatchingRecord> getNotFullyMatchedToInvoice(int C_BPartner_ID, int M_Product_ID, int C_InvoiceLine_ID, Timestamp from, Timestamp to, String trxName) {
		StringBuilder builder = new StringBuilder(NOT_FULLY_MATCHED_TO_RECEIPT);
		if (C_InvoiceLine_ID > 0) {
			builder.append(" AND mo.C_InvoiceLine_ID = ").append(C_InvoiceLine_ID);
		}
		if (M_Product_ID > 0) {
			builder.append(" AND lin.M_Product_ID=").append(M_Product_ID);
		}
		if (C_BPartner_ID > 0) {
			builder.append(" AND hdr.C_BPartner_ID=").append(C_BPartner_ID);
		}
		if (from != null) {
			builder.append(" AND ").append("hdr.DateOrdered").append(" >= ").append(DB.TO_DATE(from));
		}
		if (to != null) {
			builder.append(" AND ").append("hdr.DateOrdered").append(" <= ").append(DB.TO_DATE(to));
		}
		String sql = MRole.getDefault().addAccessSQL(
				builder.toString(), "hdr", MRole.SQL_FULLYQUALIFIED, MRole.SQL_RO)
				+ NOT_FULLY_MATCHED_TO_INVOICE_GROUP_BY;
		
		List<MatchingRecord> records = new ArrayList<>();
		try (PreparedStatement stmt = DB.prepareStatement(sql, trxName)) {
			ResultSet rs = stmt.executeQuery();
			while (rs.next()) {
				MatchingRecord matchingRecord = new MatchingRecord(rs.getInt(1), rs.getString(2), rs.getTimestamp(3), rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), 
						rs.getString(8), rs.getInt(9), rs.getBigDecimal(10), rs.getBigDecimal(11), rs.getString(12), rs.getInt(13));
				records.add(matchingRecord);
			}
		} catch (SQLException e) {
			throw new DBException(e.getMessage(), e);
		}
		return records;
	}
	
	/**
	 * @param C_BPartner_ID
	 * @param M_Product_ID
	 * @param C_InvoiceLine_ID
	 * @param from
	 * @param to
	 * @param trxName
	 * @return list of orders full or partially match to invoice
	 */
	public static List<MatchingRecord> getFullOrPartiallyMatchedToInvoice(int C_BPartner_ID, int M_Product_ID, int C_InvoiceLine_ID, Timestamp from, Timestamp to, String trxName) {
		StringBuilder builder = new StringBuilder(FULL_OR_PARTIALLY_MATCHED_TO_INVOICE);
		if (C_InvoiceLine_ID > 0) {
			builder.append(" AND mo.C_InvoiceLine_ID = ").append(C_InvoiceLine_ID);
		}
		if (M_Product_ID > 0) {
			builder.append(" AND lin.M_Product_ID=").append(M_Product_ID);
		}
		if (C_BPartner_ID > 0) {
			builder.append(" AND hdr.C_BPartner_ID=").append(C_BPartner_ID);
		}
		if (from != null) {
			builder.append(" AND ").append("hdr.DateOrdered").append(" >= ").append(DB.TO_DATE(from));
		}
		if (to != null) {
			builder.append(" AND ").append("hdr.DateOrdered").append(" <= ").append(DB.TO_DATE(to));
		}
		String sql = MRole.getDefault().addAccessSQL(
				builder.toString(), "hdr", MRole.SQL_FULLYQUALIFIED, MRole.SQL_RO)
				+ FULL_OR_PARTIALLY_MATCHED_TO_INVOICE_GROUP_BY;
		
		List<MatchingRecord> records = new ArrayList<>();
		try (PreparedStatement stmt = DB.prepareStatement(sql, trxName)) {
			ResultSet rs = stmt.executeQuery();
			while (rs.next()) {
				MatchingRecord matchingRecord = new MatchingRecord(rs.getInt(1), rs.getString(2), rs.getTimestamp(3), rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), 
						rs.getString(8), rs.getInt(9), rs.getBigDecimal(10), rs.getBigDecimal(11), rs.getString(12), rs.getInt(13));
				records.add(matchingRecord);
			}
		} catch (SQLException e) {
			throw new DBException(e.getMessage(), e);
		}
		return records;
	}
	
	/**
	 * record for matchings
	 */
	public static record MatchingRecord(int C_Order_ID, String documentNo, Timestamp documentDate, String businessPartnerName, int C_BPartner_ID, int line, int C_OrderLine_ID,
			String productName, int M_Product_ID, BigDecimal qtyOrdered, BigDecimal matchedQty, String organizationName, int AD_Org_ID) {}
	
	/**
	 * 	Create new Order by copying
	 * 	@param from order
	 * 	@param dateDoc date of the document date
	 * 	@param C_DocTypeTarget_ID target document type
	 * 	@param isSOTrx sales order 
	 * 	@param counter create counter links
	 *	@param copyASI copy line attributes Attribute Set Instance, Resaouce Assignment
	 * 	@param trxName trx
	 *	@return Order
	 */
	public static MOrder copyFrom (MOrder from, Timestamp dateDoc, 
		int C_DocTypeTarget_ID, boolean isSOTrx, boolean counter, boolean copyASI, 
		String trxName)
	{
		MOrder to = new MOrder (from.getCtx(), 0, trxName);
		to.set_TrxName(trxName);
		PO.copyValues(from, to, from.getAD_Client_ID(), from.getAD_Org_ID());
		to.set_ValueNoCheck ("C_Order_ID", I_ZERO);
		to.set_ValueNoCheck ("DocumentNo", null);
		//
		to.setDocStatus (DOCSTATUS_Drafted);		//	Draft
		to.setDocAction(DOCACTION_Complete);
		//
		to.setC_DocType_ID(0);
		to.setC_DocTypeTarget_ID (C_DocTypeTarget_ID);
		to.setIsSOTrx(isSOTrx);
		//
		to.setIsSelected (false);
		to.setDateOrdered (dateDoc);
		to.setDateAcct (dateDoc);
		to.setDatePromised (dateDoc);	//	assumption
		to.setDatePrinted(null);
		to.setIsPrinted (false);
		//
		to.setIsApproved (false);
		to.setIsCreditApproved(false);
		to.setC_Payment_ID(0);
		to.setC_CashLine_ID(0);
		//	Amounts are updated  when adding lines
		to.setGrandTotal(Env.ZERO);
		to.setTotalLines(Env.ZERO);
		//
		to.setIsDelivered(false);
		to.setIsInvoiced(false);
		to.setIsSelfService(false);
		to.setIsTransferred (false);
		to.setPosted (false);
		to.setProcessed (false);
		if (counter) {
			to.setRef_Order_ID(from.getC_Order_ID());
			MOrg org = MOrg.get(from.getCtx(), from.getAD_Org_ID());
			int counterC_BPartner_ID = org.getLinkedC_BPartner_ID(trxName);
			if (counterC_BPartner_ID == 0)
				return null;
			to.setBPartner(MBPartner.get(from.getCtx(), counterC_BPartner_ID));
		} else
			to.setRef_Order_ID(0);
		//
		if (!to.save(trxName))
			throw new IllegalStateException("Could not create Order");
		if (counter){
			// save to other counter document can re-get refer document  
			from.setRef_Order_ID(to.getC_Order_ID());
			from.saveEx();
		}

		if (to.copyLinesFrom(from, counter, copyASI) == 0)
			throw new IllegalStateException("Could not create Order Lines");
		
		// don't copy linked PO/SO
		to.setLink_Order_ID(0);
		
		return to;
	}	//	copyFrom
		
    /**
     * UUID based Constructor
     * @param ctx  Context
     * @param C_Order_UU  UUID key
     * @param trxName Transaction
     */
    public MOrder(Properties ctx, String C_Order_UU, String trxName) {
        super(ctx, C_Order_UU, trxName);
		if (Util.isEmpty(C_Order_UU))
			setInitialDefaults();
    }

	/**
	 *  @param ctx context
	 *  @param  C_Order_ID    order to load, (0 create new order)
	 *  @param trxName trx name
	 */
	public MOrder(Properties ctx, int C_Order_ID, String trxName)
	{
		this (ctx, C_Order_ID, trxName, (String[]) null);
	}	//	MOrder

	/**
	 * @param ctx
	 * @param C_Order_ID
	 * @param trxName
	 * @param virtualColumns
	 */
	public MOrder(Properties ctx, int C_Order_ID, String trxName, String... virtualColumns) {
		super(ctx, C_Order_ID, trxName, virtualColumns);
		//  New
		if (C_Order_ID == 0)
			setInitialDefaults();
	}

	/**
	 * Set the initial defaults for a new record
	 */
	private void setInitialDefaults() {
		setDocStatus(DOCSTATUS_Drafted);
		setDocAction (DOCACTION_Prepare);
		//
		setDeliveryRule (DELIVERYRULE_Availability);
		setFreightCostRule (FREIGHTCOSTRULE_FreightIncluded);
		setInvoiceRule (INVOICERULE_Immediate);
		setPaymentRule(PAYMENTRULE_OnCredit);
		setPriorityRule (PRIORITYRULE_Medium);
		setDeliveryViaRule (DELIVERYVIARULE_Pickup);
		//
		setIsDiscountPrinted (false);
		setIsSelected (false);
		setIsTaxIncluded (false);
		setIsSOTrx (true);
		setIsDropShip(false);
		setSendEMail (false);
		//
		setIsApproved(false);
		setIsPrinted(false);
		setIsCreditApproved(false);
		setIsDelivered(false);
		setIsInvoiced(false);
		setIsTransferred(false);
		setIsSelfService(false);
		//
		super.setProcessed(false);
		setProcessing(false);
		setPosted(false);

		setDateAcct (new Timestamp(System.currentTimeMillis()));
		setDatePromised (new Timestamp(System.currentTimeMillis()));
		setDateOrdered (new Timestamp(System.currentTimeMillis()));

		setFreightAmt (Env.ZERO);
		setChargeAmt (Env.ZERO);
		setTotalLines (Env.ZERO);
		setGrandTotal (Env.ZERO);
	}

	/**
	 *  Project Constructor
	 *  @param  project Project to create Order from
	 *  @param IsSOTrx sales order
	 * 	@param	DocSubTypeSO if SO DocType Target (default DocSubTypeSO_OnCredit)
	 */
	public MOrder (MProject project, boolean IsSOTrx, String DocSubTypeSO)
	{
		this (project.getCtx(), 0, project.get_TrxName());
		setAD_Client_ID(project.getAD_Client_ID());
		setAD_Org_ID(project.getAD_Org_ID());
		setC_Campaign_ID(project.getC_Campaign_ID());
		setSalesRep_ID(project.getSalesRep_ID());
		//
		setC_Project_ID(project.getC_Project_ID());
		setDescription(project.getName());
		Timestamp ts = project.getDateContract();
		if (ts != null)
			setDateOrdered (ts);
		ts = project.getDateFinish();
		if (ts != null)
			setDatePromised (ts);
		//
		setC_BPartner_ID(project.getC_BPartner_ID());
		setC_BPartner_Location_ID(project.getC_BPartner_Location_ID());
		setAD_User_ID(project.getAD_User_ID());
		//
		setM_Warehouse_ID(project.getM_Warehouse_ID());
		setM_PriceList_ID(project.getM_PriceList_ID());
		setC_PaymentTerm_ID(project.getC_PaymentTerm_ID());
		//
		setIsSOTrx(IsSOTrx);
		if (IsSOTrx)
		{
			if (DocSubTypeSO == null || DocSubTypeSO.length() == 0)
				setC_DocTypeTarget_ID(DocSubTypeSO_OnCredit);
			else
				setC_DocTypeTarget_ID(DocSubTypeSO);
		}
		else
			setC_DocTypeTarget_ID();
	}	//	MOrder

	/**
	 *  Load Constructor
	 *  @param ctx context
	 *  @param rs result set record
	 *  @param trxName transaction
	 */
	public MOrder (Properties ctx, ResultSet rs, String trxName)
	{
		super(ctx, rs, trxName);
	}	//	MOrder

	/**	Order Lines					*/
	protected MOrderLine[] 	m_lines = null;
	/**	Tax Lines					*/
	protected MOrderTax[] 	m_taxes = null;
	/** Force Creation of order		*/
	protected boolean			m_forceCreation = false;
	
	/**
	 * 	Overwrite Client/Org if required
	 * 	@param AD_Client_ID client
	 * 	@param AD_Org_ID org
	 */
	@Override
	public void setClientOrg (int AD_Client_ID, int AD_Org_ID)
	{
		super.setClientOrg(AD_Client_ID, AD_Org_ID);
	}	//	setClientOrg

	/**
	 * 	Add to Description
	 *	@param description text
	 */
	public void addDescription (String description)
	{
		String desc = getDescription();
		if (desc == null)
			setDescription(description);
		else
			setDescription(desc + " | " + description);
	}	//	addDescription
	
	/**
	 * 	Set Business Partner (Ship+Bill)
	 *	@param C_BPartner_ID bpartner
	 */
	public void setC_BPartner_ID (int C_BPartner_ID)
	{
		super.setC_BPartner_ID (C_BPartner_ID);
		super.setBill_BPartner_ID (C_BPartner_ID);
	}	//	setC_BPartner_ID
	
	/**
	 * 	Set Business Partner Location (Ship+Bill)
	 *	@param C_BPartner_Location_ID bp location
	 */
	public void setC_BPartner_Location_ID (int C_BPartner_Location_ID)
	{
		super.setC_BPartner_Location_ID (C_BPartner_Location_ID);
		super.setBill_Location_ID(C_BPartner_Location_ID);
	}	//	setC_BPartner_Location_ID

	/**
	 * 	Set Business Partner Contact (Ship+Bill)
	 *	@param AD_User_ID contact
	 */
	public void setAD_User_ID (int AD_User_ID)
	{
		super.setAD_User_ID (AD_User_ID);
		super.setBill_User_ID (AD_User_ID);
	}	//	setAD_User_ID

	/**
	 * 	Set Ship Business Partner
	 *	@param C_BPartner_ID bpartner
	 */
	public void setShip_BPartner_ID (int C_BPartner_ID)
	{
		super.setC_BPartner_ID (C_BPartner_ID);
	}	//	setShip_BPartner_ID
	
	/**
	 * 	Set Ship Business Partner Location
	 *	@param C_BPartner_Location_ID bp location
	 */
	public void setShip_Location_ID (int C_BPartner_Location_ID)
	{
		super.setC_BPartner_Location_ID (C_BPartner_Location_ID);
	}	//	setShip_Location_ID

	/**
	 * 	Set Ship Business Partner Contact
	 *	@param AD_User_ID contact
	 */
	public void setShip_User_ID (int AD_User_ID)
	{
		super.setAD_User_ID (AD_User_ID);
	}	//	setShip_User_ID
		
	/**
	 * 	Set Warehouse
	 *	@param M_Warehouse_ID warehouse
	 */
	public void setM_Warehouse_ID (int M_Warehouse_ID)
	{
		super.setM_Warehouse_ID (M_Warehouse_ID);
	}	//	setM_Warehouse_ID
	
	/**
	 * 	Set Drop Ship
	 *	@param IsDropShip drop ship
	 */
	public void setIsDropShip (boolean IsDropShip)
	{
		super.setIsDropShip (IsDropShip);
	}	//	setIsDropShip
	
	/** Sales Order Sub Type - SO	*/
	public static final String		DocSubTypeSO_Standard = "SO";
	/** Sales Order Sub Type - OB	*/
	public static final String		DocSubTypeSO_Quotation = "OB";
	/** Sales Order Sub Type - ON	*/
	public static final String		DocSubTypeSO_Proposal = "ON";
	/** Sales Order Sub Type - PR	*/
	public static final String		DocSubTypeSO_Prepay = "PR";
	/** Sales Order Sub Type - WR	*/
	public static final String		DocSubTypeSO_POS = "WR";
	/** Sales Order Sub Type - WP	*/
	public static final String		DocSubTypeSO_Warehouse = "WP";
	/** Sales Order Sub Type - WI	*/
	public static final String		DocSubTypeSO_OnCredit = "WI";
	/** Sales Order Sub Type - RM	*/
	public static final String		DocSubTypeSO_RMA = "RM";

	/**
	 * 	Set Target Sales Document Type
	 * 	@param DocSubTypeSO_x SO sub type - see DocSubTypeSO_*
	 */
	public void setC_DocTypeTarget_ID (String DocSubTypeSO_x)
	{
		String sql = "SELECT C_DocType_ID FROM C_DocType "
			+ "WHERE AD_Client_ID=? AND AD_Org_ID IN (0," + getAD_Org_ID()
			+ ") AND DocSubTypeSO=? "
			+ " AND IsSOTrx=? "
			+ " AND IsActive='Y' "
			+ "ORDER BY AD_Org_ID DESC, IsDefault DESC";
		int C_DocType_ID = DB.getSQLValue(null, sql, getAD_Client_ID(), DocSubTypeSO_x, isSOTrx() ? "Y" : "N");
		if (C_DocType_ID <= 0)
			log.severe ("Not found for AD_Client_ID=" + getAD_Client_ID () + ", SubType=" + DocSubTypeSO_x);
		else
		{
			if (log.isLoggable(Level.FINE)) log.fine("(SO) - " + DocSubTypeSO_x);
			setC_DocTypeTarget_ID (C_DocType_ID);
			setIsSOTrx(true);
		}
	}	//	setC_DocTypeTarget_ID

	/**
	 * 	Set Target Document Type.
	 * 	Standard Order or PO.
	 */
	public void setC_DocTypeTarget_ID ()
	{
		if (isSOTrx())		//	SO = Std Order
		{
			setC_DocTypeTarget_ID(DocSubTypeSO_Standard);
			return;
		}
		//	PO
		String sql = "SELECT C_DocType_ID FROM C_DocType "
			+ "WHERE AD_Client_ID=? AND AD_Org_ID IN (0," + getAD_Org_ID()
			+ ") AND DocBaseType='POO' "
			+ "ORDER BY AD_Org_ID DESC, IsDefault DESC";
		int C_DocType_ID = DB.getSQLValue(null, sql, getAD_Client_ID());
		if (C_DocType_ID <= 0)
			log.severe ("No POO found for AD_Client_ID=" + getAD_Client_ID ());
		else
		{
			if (log.isLoggable(Level.FINE)) log.fine("(PO) - " + C_DocType_ID);
			setC_DocTypeTarget_ID (C_DocType_ID);
		}
	}	//	setC_DocTypeTarget_ID

	/**
	 * 	Set Business Partner Defaults and Details.
	 * 	SOTrx should be set prior to this call.
	 * 	@param bp business partner
	 */
	public void setBPartner (MBPartner bp)
	{
		if (bp == null)
			return;

		setC_BPartner_ID(bp.getC_BPartner_ID());
		//	Defaults Payment Term
		int ii = 0;
		if (isSOTrx())
			ii = bp.getC_PaymentTerm_ID();
		else
			ii = bp.getPO_PaymentTerm_ID();
		if (ii != 0)
			setC_PaymentTerm_ID(ii);
		//	Default Price List
		if (isSOTrx())
			ii = bp.getM_PriceList_ID();
		else
			ii = bp.getPO_PriceList_ID();
		if (ii != 0)
			setM_PriceList_ID(ii);
		//	Default Delivery/Via Rule
		String ss = bp.getDeliveryRule();
		if (ss != null)
			setDeliveryRule(ss);
		ss = bp.getDeliveryViaRule();
		if (ss != null)
			setDeliveryViaRule(ss);
		//	Default Invoice/Payment Rule
		ss = bp.getInvoiceRule();
		if (ss != null)
			setInvoiceRule(ss);
		if (isSOTrx())
			ss = bp.getPaymentRule();
		else
			ss = !Util.isEmpty(bp.getPaymentRulePO()) ? bp.getPaymentRulePO() : bp.getPaymentRule();
		if (ss != null)
			setPaymentRule(ss);
		//	Sales Rep
		ii = bp.getSalesRep_ID();
		if (ii != 0)
			setSalesRep_ID(ii);

		//	Set Locations
		MBPartnerLocation[] locs = bp.getLocations(false);
		if (locs != null)
		{
			for (int i = 0; i < locs.length; i++)
			{
				if (locs[i].isShipTo())
					super.setC_BPartner_Location_ID(locs[i].getC_BPartner_Location_ID());
				if (locs[i].isBillTo())
					setBill_Location_ID(locs[i].getC_BPartner_Location_ID());
			}
			//	set to first
			if (getC_BPartner_Location_ID() == 0 && locs.length > 0)
				super.setC_BPartner_Location_ID(locs[0].getC_BPartner_Location_ID());
			if (getBill_Location_ID() == 0 && locs.length > 0)
				setBill_Location_ID(locs[0].getC_BPartner_Location_ID());
		}
		if (getC_BPartner_Location_ID() == 0)
		{	
			throw new BPartnerNoShipToAddressException(bp);
		}	
			
		if (getBill_Location_ID() == 0)
		{
			throw new BPartnerNoBillToAddressException(bp);
		}	

		//	Set Contact
		MUser[] contacts = bp.getContacts(false);
		if (contacts != null && contacts.length == 1)
			setAD_User_ID(contacts[0].getAD_User_ID());
	}	//	setBPartner

	/**
	 * 	Copy Lines From other Order
	 *	@param otherOrder order
	 *	@param counter set counter info
	 *	@param copyASI true to copy line Attribute Set Instance and Resource Assignment
	 *	@return number of lines copied
	 */
	public int copyLinesFrom (MOrder otherOrder, boolean counter, boolean copyASI)
	{
		if (isProcessed() || isPosted() || otherOrder == null)
			return 0;
		MOrderLine[] fromLines = otherOrder.getLines(false, null);
		int count = 0;
		for (int i = 0; i < fromLines.length; i++)
		{
			MOrderLine line = new MOrderLine (this);
			PO.copyValues(fromLines[i], line, getAD_Client_ID(), getAD_Org_ID());
			line.setC_Order_ID(getC_Order_ID());
			//
			line.setQtyDelivered(Env.ZERO);
			line.setQtyInvoiced(Env.ZERO);
			line.setQtyReserved(Env.ZERO);
			line.setQtyLostSales(Env.ZERO);
			line.setQtyEntered(fromLines[i].getQtyEntered());
			BigDecimal ordered = MUOMConversion.convertProductFrom (getCtx(), line.getM_Product_ID(), line.getC_UOM_ID(), line.getQtyEntered());
			line.setQtyOrdered(ordered);
			line.setDateDelivered(null);
			line.setDateInvoiced(null);
			line.setOrder(this);
			line.set_ValueNoCheck ("C_OrderLine_ID", I_ZERO);	//	new
			if (!counter && MOrder.STATUS_Closed.equals(otherOrder.getDocStatus()))
				line.setDescription(line.getDescriptionStrippingCloseTag());
			//	References
			if (!copyASI)
			{
				line.setM_AttributeSetInstance_ID(0);
				line.setS_ResourceAssignment_ID(0);
			}
			if (counter)
				line.setRef_OrderLine_ID(fromLines[i].getC_OrderLine_ID());
			else
				line.setRef_OrderLine_ID(0);

			// don't copy linked lines
			line.setLink_OrderLine_ID(0);
			//	Tax
			if (getC_BPartner_ID() != otherOrder.getC_BPartner_ID())
				line.setTax();		//	recalculate
			//
			//
			line.setProcessed(false);
			if (line.save(get_TrxName()))
				count++;
			//	Cross Link
			if (counter)
			{
				fromLines[i].setRef_OrderLine_ID(line.getC_OrderLine_ID());
				fromLines[i].saveEx(get_TrxName());
			}
		}
		if (fromLines.length != count)
			log.log(Level.SEVERE, "Line difference - From=" + fromLines.length + " <> Saved=" + count);
		return count;
	}	//	copyLinesFrom
	
	/**
	 * 	String Representation
	 *	@return info
	 */
	@Override
	public String toString ()
	{
		StringBuilder sb = new StringBuilder ("MOrder[")
			.append(get_ID()).append("-").append(getDocumentNo())
			.append(",IsSOTrx=").append(isSOTrx())
			.append(",C_DocType_ID=").append(getC_DocType_ID())
			.append(", GrandTotal=").append(getGrandTotal())
			.append ("]");
		return sb.toString ();
	}	//	toString

	/**
	 * 	Get Document Info
	 *	@return document info (untranslated)
	 */
	@Override
	public String getDocumentInfo()
	{
		MDocType dt = MDocType.get(getCtx(), getC_DocType_ID() > 0 ? getC_DocType_ID() : getC_DocTypeTarget_ID());
		return dt.getNameTrl() + " " + getDocumentNo();
	}	//	getDocumentInfo

	/**
	 * 	Create PDF
	 *	@return File or null
	 */
	@Override
	public File createPDF ()
	{
		return createPDF (null);
	}	//	getPDF

	/**
	 * 	Create PDF file
	 *	@param file output file
	 *	@return file if success
	 */
	public File createPDF (File file)
	{
		ReportEngine re = ReportEngine.get (getCtx(), ReportEngine.ORDER, getC_Order_ID(), get_TrxName());
		if (re == null)
			return null;
		return re.getPDF(file);
	}	//	createPDF
	
	/**
	 * 	Set Price List (and Currency, TaxIncluded) when valid
	 * 	@param M_PriceList_ID price list
	 */
	@Override
	public void setM_PriceList_ID (int M_PriceList_ID)
	{
		MPriceList pl = MPriceList.get(getCtx(), M_PriceList_ID, null);
		if (pl.get_ID() == M_PriceList_ID)
		{
			super.setM_PriceList_ID(M_PriceList_ID);
			setC_Currency_ID(pl.getC_Currency_ID());
			setIsTaxIncluded(pl.isTaxIncluded());
		}
	}	//	setM_PriceList_ID
	
	/**
	 * 	Get Lines of Order
	 * 	@param whereClause where clause or null (must start with AND)
	 * 	@param orderClause order clause or null
	 * 	@return lines
	 */
	public MOrderLine[] getLines (String whereClause, String orderClause)
	{
		//red1 - using new Query class from Teo / Victor's MDDOrder.java implementation
		StringBuilder whereClauseFinal = new StringBuilder(MOrderLine.COLUMNNAME_C_Order_ID+"=? ");
		if (!Util.isEmpty(whereClause, true))
			whereClauseFinal.append(whereClause);
		if (Util.isEmpty(orderClause, true))
			orderClause = MOrderLine.COLUMNNAME_Line;
		//
		List<MOrderLine> list = new Query(getCtx(), I_C_OrderLine.Table_Name, whereClauseFinal.toString(), get_TrxName())
										.setParameters(get_ID())
										.setOrderBy(orderClause)
										.list();
		for (MOrderLine ol : list) {
			ol.setHeaderInfo(this);
		}
		//
		return list.toArray(new MOrderLine[list.size()]);		
	}	//	getLines

	/**
	 * 	Get Lines of Order
	 * 	@param requery true to re-query from DB
	 * 	@param orderBy optional order by columns
	 * 	@return lines
	 */
	public MOrderLine[] getLines (boolean requery, String orderBy)
	{
		if (m_lines != null && !requery) {
			set_TrxName(m_lines, get_TrxName());
			return m_lines;
		}
		//
		String orderClause = "";
		if (orderBy != null && orderBy.length() > 0)
			orderClause += orderBy;
		else
			orderClause += "Line,C_OrderLine_ID";
		m_lines = getLines(null, orderClause);
		return m_lines;
	}	//	getLines

	/**
	 * 	Get Lines of Order.
	 * 	@return lines
	 */
	public MOrderLine[] getLines()
	{
		return getLines(false, null);
	}	//	getLines
	
	/**
	 * 	Renumber Lines
	 *	@param step start and step
	 */
	public void renumberLines (int step)
	{
		int number = step;
		MOrderLine[] lines = getLines(true, null);	//	Line is default
		for (int i = 0; i < lines.length; i++)
		{
			MOrderLine line = lines[i];
			line.setLine(number);
			line.saveEx(get_TrxName());
			number += step;
		}
		m_lines = null;
	}	//	renumberLines
	
	/**
	 * 	Does the Order Line belong to this Order
	 *	@param C_OrderLine_ID line
	 *	@return true if part of the order
	 */
	public boolean isOrderLine(int C_OrderLine_ID)
	{
		if (m_lines == null)
			getLines();
		for (int i = 0; i < m_lines.length; i++)
			if (m_lines[i].getC_OrderLine_ID() == C_OrderLine_ID)
				return true;
		return false;
	}	//	isOrderLine

	/**
	 * 	Get Taxes of Order
	 *	@param requery true to re-query from DB
	 *	@return array of taxes
	 */
	public MOrderTax[] getTaxes(boolean requery)
	{
		if (m_taxes != null && !requery)
			return m_taxes;
		//
		List<MOrderTax> list = new Query(getCtx(), I_C_OrderTax.Table_Name, "C_Order_ID=?", get_TrxName())
									.setParameters(get_ID())
									.list();
		m_taxes = list.toArray(new MOrderTax[list.size()]);
		return m_taxes;
	}	//	getTaxes
		
	/**
	 * 	Get Invoices of Order
	 * 	@return invoices
	 */
	public MInvoice[] getInvoices()
	{
		final String whereClause = "EXISTS (SELECT 1 FROM C_InvoiceLine il, C_OrderLine ol"
							        +" WHERE il.C_Invoice_ID=C_Invoice.C_Invoice_ID"
							        		+" AND il.C_OrderLine_ID=ol.C_OrderLine_ID"
							        		+" AND ol.C_Order_ID=?)";
		List<MInvoice> list = new Query(getCtx(), I_C_Invoice.Table_Name, whereClause, get_TrxName())
									.setParameters(get_ID())
									.setOrderBy("C_Invoice_ID DESC")
									.list();
		return list.toArray(new MInvoice[list.size()]);
	}	//	getInvoices

	/**
	 * 	Get latest Invoice of Order
	 * 	@return invoice id or 0
	 */
	public int getC_Invoice_ID()
	{
 		String sql = "SELECT C_Invoice_ID FROM C_Invoice "
			+ "WHERE C_Order_ID=? AND DocStatus IN ('CO','CL') "
			+ "ORDER BY C_Invoice_ID DESC";
		int C_Invoice_ID = DB.getSQLValue(get_TrxName(), sql, get_ID());
		return C_Invoice_ID;
	}	//	getC_Invoice_ID

	/**
	 * 	Get Shipments of Order
	 * 	@return shipments
	 */
	public MInOut[] getShipments()
	{
		final String whereClause = "EXISTS (SELECT 1 FROM M_InOutLine iol, C_OrderLine ol"
			+" WHERE iol.M_InOut_ID=M_InOut.M_InOut_ID"
			+" AND iol.C_OrderLine_ID=ol.C_OrderLine_ID"
			+" AND ol.C_Order_ID=?)";
		List<MInOut> list = new Query(getCtx(), MInOut.Table_Name, whereClause, get_TrxName())
									.setParameters(get_ID())
									.setOrderBy("M_InOut_ID DESC")
									.list();
		return list.toArray(new MInOut[list.size()]);
	}	//	getShipments

	/**
	 *	Get ISO Code of Currency
	 *	@return Currency ISO
	 */
	public String getCurrencyISO()
	{
		return MCurrency.getISO_Code (getCtx(), getC_Currency_ID());
	}	//	getCurrencyISO
	
	/**
	 * 	Get Currency Precision
	 *	@return precision
	 */
	public int getPrecision()
	{
		return MCurrency.getStdPrecision(getCtx(), getC_Currency_ID());
	}	//	getPrecision

	/**
	 * 	Get Document Status Name
	 *	@return Document Status Name
	 */
	public String getDocStatusName()
	{
		return MRefList.getListName(getCtx(), 131, getDocStatus());
	}	//	getDocStatusName

	/**
	 * 	Set DocAction
	 *	@param DocAction DocAction.ACTION_*
	 */
	@Override
	public void setDocAction (String DocAction)
	{
		setDocAction (DocAction, false);
	}	//	setDocAction

	/**
	 * 	Set DocAction
	 *	@param DocAction DocAction.ACTION_*
	 *	@param forceCreation force creation
	 */
	public void setDocAction (String DocAction, boolean forceCreation)
	{
		super.setDocAction (DocAction);
		m_forceCreation = forceCreation;
	}	//	setDocAction
	
	/**
	 * 	Set Processed.
	 * 	Propagate to Lines/Taxes
	 *	@param processed processed
	 */
	@Override
	public void setProcessed (boolean processed)
	{
		super.setProcessed (processed);
		if (get_ID() == 0)
			return;
		String set = "SET Processed='"
			+ (processed ? "Y" : "N")
			+ "' WHERE C_Order_ID=" + getC_Order_ID();
		int noLine = DB.executeUpdateEx("UPDATE C_OrderLine " + set, get_TrxName());
		int noTax = DB.executeUpdateEx("UPDATE C_OrderTax " + set, get_TrxName());
		m_lines = null;
		m_taxes = null;
		if (log.isLoggable(Level.FINE)) log.fine("setProcessed - " + processed + " - Lines=" + noLine + ", Tax=" + noTax);
	}	//	setProcessed
	
	/**
	 * 	Validate Order Pay Schedule
	 *	@return pay schedule is valid
	 */
	public boolean validatePaySchedule()
	{
		MOrderPaySchedule[] schedule = MOrderPaySchedule.getOrderPaySchedule
			(getCtx(), getC_Order_ID(), 0, get_TrxName());
		if (log.isLoggable(Level.FINE)) log.fine("#" + schedule.length);
		if (schedule.length == 0)
		{
			setIsPayScheduleValid(false);
			return false;
		}
		//	Add up due amounts
		BigDecimal total = Env.ZERO;
		for (int i = 0; i < schedule.length; i++)
		{
			schedule[i].setParent(this);
			BigDecimal due = schedule[i].getDueAmt();
			if (due != null)
				total = total.add(due);
		}
		boolean valid = getGrandTotal().compareTo(total) == 0;
		setIsPayScheduleValid(valid);
		
		//	Update Schedule Lines
		for (int i = 0; i < schedule.length; i++)
		{
			if (schedule[i].isValid() != valid)
			{
				schedule[i].setIsValid(valid);
				schedule[i].saveEx(get_TrxName());				
			}
		}
		return valid;
	}	//	validatePaySchedule
	
	private static final ThreadLocal<Boolean> recursiveCall = new ThreadLocal<>();
	
	@Override
	protected boolean beforeSave (boolean newRecord)
	{
		//	Client/Org Check
		if (getAD_Org_ID() == 0)
		{
			int context_AD_Org_ID = Env.getAD_Org_ID(getCtx());
			if (context_AD_Org_ID != 0)
			{
				setAD_Org_ID(context_AD_Org_ID);
				log.warning("Changed Org to Context=" + context_AD_Org_ID);
			}
		}
		if (getAD_Client_ID() == 0)
		{
			m_processMsg = "AD_Client_ID = 0";
			return false;
		}
		
		//	New Record Doc Type - make sure DocType set to 0
		if (newRecord && getC_DocType_ID() == 0)
			setC_DocType_ID (0);

		//	Default Warehouse
		if (getM_Warehouse_ID() == 0)
		{
			int ii = Env.getContextAsInt(getCtx(), Env.M_WAREHOUSE_ID);
			if (ii != 0)
				setM_Warehouse_ID(ii);
			else
			{
				throw new FillMandatoryException(COLUMNNAME_M_Warehouse_ID);
			}
		}
		MWarehouse wh = MWarehouse.get(getCtx(), getM_Warehouse_ID());
		//	Validate warehouse and order document belong to the same organization
		if (newRecord 
			|| is_ValueChanged("AD_Org_ID") || is_ValueChanged("M_Warehouse_ID"))
		{
			if (wh.getAD_Org_ID() != getAD_Org_ID())
				log.saveWarning("WarehouseOrgConflict", "");
		}

		//	Validate change of warehouse against existing order line
		if (!newRecord && is_ValueChanged("M_Warehouse_ID"))
		{
			MOrderLine[] lines = getLines(false,null);
			for (int i = 0; i < lines.length; i++)
			{
				if (!lines[i].canChangeWarehouse())
					return false;
			}
		}

		// Validate C_BPartner_Location_ID and AD_User_ID after edit of C_BPartner_ID
		final String sqlBPIdFromLoc  = "SELECT C_BPartner_ID FROM C_BPartner_Location WHERE C_BPartner_Location_ID=?";
		final String sqlBPIdFromUser = "SELECT C_BPartner_ID FROM AD_User WHERE AD_User_ID=?";
		if (is_new() || is_ValueChanged(COLUMNNAME_C_BPartner_ID)) {
			if (getC_BPartner_Location_ID() > 0) {
				int bpId = DB.getSQLValueEx(get_TrxName(), sqlBPIdFromLoc, getC_BPartner_Location_ID());
				if (bpId != getC_BPartner_ID()) {
					set_ValueNoCheck(COLUMNNAME_C_BPartner_Location_ID, null);
				}
			}
			if (getAD_User_ID() >= 0) {
				int bpId = DB.getSQLValueEx(get_TrxName(), sqlBPIdFromUser, getAD_User_ID());
				if (bpId != getC_BPartner_ID()) {
					set_Value(COLUMNNAME_AD_User_ID, null);
				}
			}
		}
		// Validate Bill_Location_ID and Bill_User_ID after edit of Bill_BPartner_ID
		if (is_new() || is_ValueChanged(COLUMNNAME_Bill_BPartner_ID)) {
			if (getBill_Location_ID() > 0) {
				int bpId = DB.getSQLValueEx(get_TrxName(), sqlBPIdFromLoc, getBill_Location_ID());
				if (bpId != getBill_BPartner_ID()) {
					set_Value(COLUMNNAME_Bill_Location_ID, null);
				}
			}
			if (getBill_User_ID() >= 0) {
				int bpId = DB.getSQLValueEx(get_TrxName(), sqlBPIdFromUser, getBill_User_ID());
				if (bpId != getBill_BPartner_ID()) {
					setBill_User_ID(-1);
				}
			}
		}
		
		if (getC_BPartner_Location_ID() == 0)
			setBPartner(new MBPartner(getCtx(), getC_BPartner_ID(), get_TrxName()));
		//	Default Bill_BPartner_ID to C_BPartner_ID
		if (getBill_BPartner_ID() == 0)
		{
			setBill_BPartner_ID(getC_BPartner_ID());
			setBill_Location_ID(getC_BPartner_Location_ID());
		}
		//	Default Bill_Location_ID to C_BPartner_Location_ID
		if (getBill_Location_ID() == 0)
			setBill_Location_ID(getC_BPartner_Location_ID());

		//	Default Price List
		if (getM_PriceList_ID() == 0)
		{
			int ii = DB.getSQLValueEx(null,
				"SELECT M_PriceList_ID FROM M_PriceList "
				+ "WHERE AD_Client_ID=? AND IsSOPriceList=? AND IsActive=? "
				+ "ORDER BY IsDefault DESC", getAD_Client_ID(), isSOTrx(), true);
			if (ii != 0)
				setM_PriceList_ID (ii);
		}
		//	Default Currency
		if (getC_Currency_ID() == 0)
		{
			String sql = "SELECT C_Currency_ID FROM M_PriceList WHERE M_PriceList_ID=?";
			int ii = DB.getSQLValue (null, sql, getM_PriceList_ID());
			if (ii != 0)
				setC_Currency_ID (ii);
			else
				setC_Currency_ID(Env.getContextAsInt(getCtx(), Env.C_CURRENCY_ID));
		}

		//	Default Sales Rep
		if (getSalesRep_ID() == 0)
		{
			int ii = Env.getContextAsInt(getCtx(), Env.SALESREP_ID);
			if (ii != 0)
				setSalesRep_ID (ii);
		}

		//	Default Document Type
		if (getC_DocTypeTarget_ID() == 0)
			setC_DocTypeTarget_ID(DocSubTypeSO_Standard);

		//	Default Payment Term
		if (getC_PaymentTerm_ID() == 0)
		{
			int ii = Env.getContextAsInt(getCtx(), Env.C_PAYMENTTERM_ID);
			if (ii != 0)
				setC_PaymentTerm_ID(ii);
			else
			{
				String sql = "SELECT C_PaymentTerm_ID FROM C_PaymentTerm WHERE AD_Client_ID=? AND IsDefault='Y' AND IsActive='Y'";
				ii = DB.getSQLValue(null, sql, getAD_Client_ID());
				if (ii != 0)
					setC_PaymentTerm_ID (ii);
			}
		}

		// IDEMPIERE-63
		// If document have been processed, we can't change 
		// C_DocTypeTarget_ID or C_DocType_ID if DocType.IsOverwriteSeqOnComplete=Y.
		// Also, can't change DateDoc if DocType.IsOverwriteDateOnComplete=Y.
		BigDecimal previousProcessedOn = (BigDecimal) get_ValueOld(COLUMNNAME_ProcessedOn);
		if (! newRecord && previousProcessedOn != null && previousProcessedOn.signum() > 0) {
			int previousDocTypeID = (Integer) get_ValueOld(COLUMNNAME_C_DocTypeTarget_ID);
			MDocType previousdt = MDocType.get(getCtx(), previousDocTypeID);
			if (is_ValueChanged(COLUMNNAME_C_DocType_ID) || is_ValueChanged(COLUMNNAME_C_DocTypeTarget_ID)) {
				if (previousdt.isOverwriteSeqOnComplete()) {
					log.saveError("Error", Msg.getMsg(getCtx(), "CannotChangeProcessedDocType"));
					return false; 
				}
			}
			if (is_ValueChanged(COLUMNNAME_DateOrdered)) {
				if (previousdt.isOverwriteDateOnComplete()) {
					log.saveError("Error", Msg.getMsg(getCtx(), "CannotChangeProcessedDate"));
					return false; 
				}
			}
		}

		// IDEMPIERE-1597 Price List and Date must be not-updateable
		if (!newRecord && (is_ValueChanged(COLUMNNAME_M_PriceList_ID) || is_ValueChanged(COLUMNNAME_DateOrdered))) {
			int cnt = DB.getSQLValueEx(get_TrxName(), "SELECT COUNT(*) FROM C_OrderLine WHERE C_Order_ID=? AND M_Product_ID>0", getC_Order_ID());
			if (cnt > 0) {
				// Disallow change of price list if there are existing order lines
				if (is_ValueChanged(COLUMNNAME_M_PriceList_ID)) {
					log.saveError("Error", Msg.getMsg(getCtx(), "CannotChangePl"));
					return false;
				}
				// Validate price list is valid for updated DateInvoiced
				if (is_ValueChanged(COLUMNNAME_DateOrdered)) {
					MPriceList pList =  MPriceList.get(getCtx(), getM_PriceList_ID(), null);
					MPriceListVersion plOld = pList.getPriceListVersion((Timestamp)get_ValueOld(COLUMNNAME_DateOrdered));
					MPriceListVersion plNew = pList.getPriceListVersion((Timestamp)get_Value(COLUMNNAME_DateOrdered));
					if (plNew == null || !plNew.equals(plOld)) {
						log.saveError("Error", Msg.getMsg(getCtx(), "CannotChangeDateOrdered"));
						return false;
					}
				}
			}
		}

		// IDEMPIERE-4318 Validation - Prepay Order must not allow Cash payment rule
		MDocType dt = MDocType.get(getCtx(), getC_DocTypeTarget_ID());
		if (   MDocType.DOCSUBTYPESO_PrepayOrder.equals(dt.getDocSubTypeSO())
			&& PAYMENTRULE_Cash.equals(getPaymentRule())) {
			log.saveError("Error", Msg.parseTranslation(getCtx(), "@Invalid@ @PaymentRule@"));
			return false;
		}

		// Validate payment term and update IsPayScheduleValid
		if (!Boolean.TRUE.equals(recursiveCall.get()) && (!newRecord && is_ValueChanged(COLUMNNAME_C_PaymentTerm_ID))) {
			recursiveCall.set(Boolean.TRUE);
			try {
				MPaymentTerm pt = new MPaymentTerm (getCtx(), getC_PaymentTerm_ID(), get_TrxName());
				boolean valid = pt.applyOrder(this);
				setIsPayScheduleValid(valid);
			} catch (Exception e) {
				throw e;
			} finally {
				recursiveCall.remove();
			}
		}

		return true;
	}	//	beforeSave
		
	@Override
	protected boolean afterSave (boolean newRecord, boolean success)
	{
		if (!success || newRecord)
			return success;

		// Propagate changes to not completed/reversed/closed invoices
		String propagateColsSysCfg = MSysConfig.getValue(MSysConfig.ORDER_COLUMNS_TO_COPY_TO_NOT_COMPLETED_INVOICES,
				"Description,POReference,PaymentRule,C_PaymentTerm_ID,DateAcct", getAD_Client_ID(), getAD_Org_ID());
		if (!Util.isEmpty(propagateColsSysCfg, true)) {
			String[] propagateCols = propagateColsSysCfg.split(",");
			boolean propagateColChanged = false;
			for (String propagateCol : propagateCols) {
				String trimmedCol = propagateCol.trim();
				if (get_ColumnIndex(trimmedCol) >= 0 && is_ValueChanged(trimmedCol)) {
					propagateColChanged = true;
					break;
				}
			}
			if (propagateColChanged) {
				List<MInvoice> relatedInvoices = new Query(getCtx(), MInvoice.Table_Name,
						"C_Order_ID=? AND Processed='N' AND DocStatus NOT IN ('CO','RE','CL')", get_TrxName())
						.setParameters(getC_Order_ID())
						.list();
				if (relatedInvoices.size() > 0) {
					for (String propagateCol : propagateCols) {
						String trimmedCol = propagateCol.trim();
						if (get_ColumnIndex(trimmedCol) >= 0 && is_ValueChanged(trimmedCol)) {
							Object newValue = get_Value(trimmedCol);
							for (MInvoice relatedInvoice : relatedInvoices) {
								relatedInvoice.set_Value(trimmedCol, newValue);
							}
						}
					}
					for (MInvoice relatedInvoice : relatedInvoices) {
						relatedInvoice.saveEx();
					}
				}
			}
		}

		//	Sync Lines
		if (   is_ValueChanged("AD_Org_ID")
		    || is_ValueChanged(MOrder.COLUMNNAME_C_BPartner_ID)
		    || is_ValueChanged(MOrder.COLUMNNAME_C_BPartner_Location_ID)
		    || is_ValueChanged(MOrder.COLUMNNAME_DateOrdered)
		    || is_ValueChanged(MOrder.COLUMNNAME_DatePromised)
		    || is_ValueChanged(MOrder.COLUMNNAME_M_Warehouse_ID)
		    || is_ValueChanged(MOrder.COLUMNNAME_M_Shipper_ID)
		    || is_ValueChanged(MOrder.COLUMNNAME_C_Currency_ID)) {
			MOrderLine[] lines = getLines();
			for (MOrderLine line : lines) {
				if (is_ValueChanged("AD_Org_ID"))
					line.setAD_Org_ID(getAD_Org_ID());
				if (is_ValueChanged(MOrder.COLUMNNAME_C_BPartner_ID))
					line.setC_BPartner_ID(getC_BPartner_ID());
				if (is_ValueChanged(MOrder.COLUMNNAME_C_BPartner_Location_ID))
					line.setC_BPartner_Location_ID(getC_BPartner_Location_ID());
				if (is_ValueChanged(MOrder.COLUMNNAME_DateOrdered))
					line.setDateOrdered(getDateOrdered());
				if (is_ValueChanged(MOrder.COLUMNNAME_DatePromised))
					line.setDatePromised(getDatePromised());
				if (is_ValueChanged(MOrder.COLUMNNAME_M_Warehouse_ID))
					line.setM_Warehouse_ID(getM_Warehouse_ID());
				if (is_ValueChanged(MOrder.COLUMNNAME_M_Shipper_ID))
					line.setM_Shipper_ID(getM_Shipper_ID());
				if (is_ValueChanged(MOrder.COLUMNNAME_C_Currency_ID))
					line.setC_Currency_ID(getC_Currency_ID());
				line.saveEx();
			}
			if (is_ValueChanged(MOrder.COLUMNNAME_AD_Org_ID)) {
				for (MOrderPaySchedule orderPaySchedule : MOrderPaySchedule.getOrderPaySchedule(getCtx(),this.getC_Order_ID(), 0, get_TrxName())) {
					orderPaySchedule.setAD_Org_ID(getAD_Org_ID());
					orderPaySchedule.saveEx();
				}
			}
			
		}
		//
		return true;
	}	//	afterSave
	
	@Override
	protected boolean beforeDelete ()
	{
		if (isProcessed())
			return false;
		// automatic deletion of lines is driven by model cascade definition in dictionary - see IDEMPIERE-2060
		return true;
	}	//	beforeDelete
	
	/**
	 * 	Process document
	 *	@param processAction document action
	 *	@return true if performed
	 */
	@Override
	public boolean processIt (String processAction)
	{
		m_processMsg = null;
		DocumentEngine engine = new DocumentEngine (this, getDocStatus());
		return engine.processIt (processAction, getDocAction());
	}	//	processIt
	
	/**	Process Message 			*/
	protected String		m_processMsg = null;
	/**	Just Prepared Flag			*/
	protected boolean		m_justPrepared = false;

	/**
	 * 	Unlock Document.
	 * 	@return true if success 
	 */
	@Override
	public boolean unlockIt()
	{
		if (log.isLoggable(Level.INFO)) log.info("unlockIt - " + toString());
		setProcessing(false);
		return true;
	}	//	unlockIt
	
	/**
	 * 	Invalidate Document
	 * 	@return true if success 
	 */
	@Override
	public boolean invalidateIt()
	{
		if (log.isLoggable(Level.INFO)) log.info(toString());
		setDocAction(DOCACTION_Prepare);
		return true;
	}	//	invalidateIt
		
	/**
	 *	Prepare Document
	 * 	@return new status (In Progress or Invalid) 
	 */
	@Override
	public String prepareIt()
	{
		if (log.isLoggable(Level.INFO)) log.info(toString());
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this, ModelValidator.TIMING_BEFORE_PREPARE);
		if (m_processMsg != null)
			return DocAction.STATUS_Invalid;
		MDocType dt = MDocType.get(getCtx(), getC_DocTypeTarget_ID());

		//	Std Period open?
		if (!MPeriod.isOpen(getCtx(), getDateAcct(), dt.getDocBaseType(), getAD_Org_ID()))
		{
			m_processMsg = "@PeriodClosed@";
			return DocAction.STATUS_Invalid;
		}
		
		if (isSOTrx() && getDeliveryViaRule().equals(DELIVERYVIARULE_Shipper))
		{
			if (getM_Shipper_ID() == 0)
			{
				m_processMsg = "@FillMandatory@" + Msg.getElement(getCtx(), COLUMNNAME_M_Shipper_ID);
				return DocAction.STATUS_Invalid;
			}
			
			if (!calculateFreightCharge())
				return DocAction.STATUS_Invalid;
		}

		//	Lines
		MOrderLine[] lines = getLines(true, MOrderLine.COLUMNNAME_M_Product_ID);
		if (lines.length == 0)
		{
			m_processMsg = "@NoLines@";
			return DocAction.STATUS_Invalid;
		}
				
		// Bug 1564431
		if (MOrder.DELIVERYRULE_CompleteOrder.equals(getDeliveryRule()) )
		{
			for (int i = 0; i < lines.length; i++) 
			{
				MOrderLine line = lines[i];
				MProduct product = line.getProduct();
				if (product != null && product.isExcludeAutoDelivery())
				{
					m_processMsg = "@M_Product_ID@ "+product.getValue()+" @IsExcludeAutoDelivery@";
					return DocAction.STATUS_Invalid;
				}
				if (line.getDatePromised() != null && !line.getDatePromised().equals(getDatePromised()))
				{
					m_processMsg = "@Line@ " + line.getLine() + " - @Invalid@ @DatePromised@";
					return DocAction.STATUS_Invalid;
				}
			}
		}
		
		//	Convert DocType to Target
		if (getC_DocType_ID() != getC_DocTypeTarget_ID() )
		{
			//	Cannot change Std to anything else if different warehouses
			if (getC_DocType_ID() != 0)
			{
				MDocType dtOld = MDocType.get(getCtx(), getC_DocType_ID());
				if (MDocType.DOCSUBTYPESO_StandardOrder.equals(dtOld.getDocSubTypeSO())		//	From SO
					&& !MDocType.DOCSUBTYPESO_StandardOrder.equals(dt.getDocSubTypeSO()))	//	To !SO
				{
					for (int i = 0; i < lines.length; i++)
					{
						if (lines[i].getM_Warehouse_ID() != getM_Warehouse_ID())
						{
							log.warning("different Warehouse " + lines[i]);
							m_processMsg = "@CannotChangeDocType@";
							return DocAction.STATUS_Invalid;
						}
					}
				}
			}
			
			//	New or in Progress/Invalid
			if (DOCSTATUS_Drafted.equals(getDocStatus()) 
				|| DOCSTATUS_InProgress.equals(getDocStatus())
				|| DOCSTATUS_Invalid.equals(getDocStatus())
				|| getC_DocType_ID() == 0)
			{
				setC_DocType_ID(getC_DocTypeTarget_ID());
			}
			else	//	convert only if offer
			{
				if (dt.isOffer())
					setC_DocType_ID(getC_DocTypeTarget_ID());
				else
				{
					m_processMsg = "@CannotChangeDocType@";
					return DocAction.STATUS_Invalid;
				}
			}
		}	//	convert DocType

		//	Mandatory Product Attribute Set Instance
		for (MOrderLine line : getLines()) {
			if (line.getM_Product_ID() > 0 && line.getM_AttributeSetInstance_ID() == 0) {
				MProduct product = line.getProduct();
				if (product.isASIMandatoryFor(null, isSOTrx())) {
					if (product.getAttributeSet() != null && !product.getAttributeSet().excludeTableEntry(MOrderLine.Table_ID, isSOTrx())) {
						StringBuilder msg = new StringBuilder("@M_AttributeSet_ID@ @IsMandatory@ (@Line@ #")
							.append(line.getLine())
							.append(", @M_Product_ID@=")
							.append(product.getValue())
							.append(")");
						m_processMsg = msg.toString();
						return DocAction.STATUS_Invalid;
					}
				}
			}
		}

		//	Lines
		if (explodeBOM())
			lines = getLines(true, MOrderLine.COLUMNNAME_M_Product_ID);

		// Reserve stock if does not generate shipment on complete
		if (!evalAutoGenerateInOutRule(dt.getDocSubTypeSO(), dt.isAutoGenerateInout())) {
			if (!reserveStock(dt, lines))
			{
				String innerMsg = CLogger.retrieveErrorString("");
				m_processMsg = "Cannot reserve Stock";
				if (! Util.isEmpty(innerMsg))
					m_processMsg = m_processMsg + " -> " + innerMsg;
				return DocAction.STATUS_Invalid;
			}
		}
		if (!calculateTaxTotal())
		{
			m_processMsg = "Error calculating tax";
			return DocAction.STATUS_Invalid;
		}
		
		if (   getGrandTotal().signum() != 0
			&& (PAYMENTRULE_OnCredit.equals(getPaymentRule()) || PAYMENTRULE_DirectDebit.equals(getPaymentRule())))
		{
			if (!createPaySchedule())
			{
				m_processMsg = "@ErrorPaymentSchedule@";
				return DocAction.STATUS_Invalid;
			}
		} else {
			if (MOrderPaySchedule.getOrderPaySchedule(getCtx(), getC_Order_ID(), 0, get_TrxName()).length > 0) 
			{
				m_processMsg = "@ErrorPaymentSchedule@";
				return DocAction.STATUS_Invalid;
			}
		}
		
		//	Credit Check
		ICreditManager creditManager = Core.getCreditManager(this);
		if (creditManager != null)
		{
			CreditStatus status = creditManager.checkCreditStatus(DOCACTION_Prepare);
			if (status.isError())
			{
				m_processMsg = status.getErrorMsg();
				return DocAction.STATUS_Invalid;
			}
		}
		
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this, ModelValidator.TIMING_AFTER_PREPARE);
		if (m_processMsg != null)
			return DocAction.STATUS_Invalid;
		
		m_justPrepared = true;
		return DocAction.STATUS_InProgress;
	}	//	prepareIt
	
	/**
	 * Calculate freight charge and create order line for freight charge (if needed).
	 * @return true if no error
	 */
	protected boolean calculateFreightCharge()
	{
		MClientInfo ci = MClientInfo.get(getCtx(), getAD_Client_ID(), get_TrxName());
		if (ci.getC_ChargeFreight_ID() == 0 && ci.getM_ProductFreight_ID() == 0)
		{
			m_processMsg = "Product or Charge for Freight is not defined at Tenant window > Tenant Info tab";
			return false;
		}
		
		MOrderLine[] ols = getLines(false, MOrderLine.COLUMNNAME_Line);
		if (ols.length == 0)
		{
			m_processMsg = "@NoLines@";
			return false;
		}
		
		MOrderLine freightLine = null;
		for (MOrderLine ol : ols)
		{
			if ((ol.getM_Product_ID() > 0 && ol.getM_Product_ID() == ci.getM_ProductFreight_ID()) ||
					(ol.getC_Charge_ID() > 0 && ol.getC_Charge_ID() == ci.getC_ChargeFreight_ID()))
			{
				freightLine = ol;
				break;
			}
		}
		
		if (getFreightCostRule().equals(FREIGHTCOSTRULE_FreightIncluded))
		{
			if (freightLine != null)
			{
				boolean deleted = freightLine.delete(false);
				if (!deleted)
				{
					freightLine.setC_BPartner_Location_ID(getC_BPartner_Location_ID());
					freightLine.setM_Shipper_ID(getM_Shipper_ID());
					freightLine.setQty(BigDecimal.ONE);
					freightLine.setPrice(BigDecimal.ZERO);
					freightLine.saveEx();
				}
			}
		}
		else if (getFreightCostRule().equals(FREIGHTCOSTRULE_FixPrice))
		{
			if (freightLine == null)
			{
				freightLine = new MOrderLine(this);
			
				if (ci.getC_ChargeFreight_ID() > 0)
					freightLine.setC_Charge_ID(ci.getC_ChargeFreight_ID());
				else if (ci.getM_ProductFreight_ID() > 0)
					freightLine.setM_Product_ID(ci.getM_ProductFreight_ID());
				else
					throw new AdempiereException("Product or Charge for Freight is not defined at Tenant window > Tenant Info tab");
			}
			
			freightLine.setC_BPartner_Location_ID(getC_BPartner_Location_ID());
			freightLine.setM_Shipper_ID(getM_Shipper_ID());
			freightLine.setQty(BigDecimal.ONE);
			freightLine.setPrice(getFreightAmt());
			freightLine.saveEx();
		}
		else if (getFreightCostRule().equals(FREIGHTCOSTRULE_Calculated))
		{
			if (ci.getC_UOM_Weight_ID() == 0)
			{
				m_processMsg = "UOM for Weight is not defined at Tenant window > Tenant Info tab";
				return false;
			}
			if (ci.getC_UOM_Length_ID() == 0)
			{
				m_processMsg = "UOM for Length is not defined at Tenant window > Tenant Info ta";
				return false;
			}
			
			for (MOrderLine ol : ols)
			{
				if ((ol.getM_Product_ID() > 0 && ol.getM_Product_ID() == ci.getM_ProductFreight_ID()) ||
						(ol.getC_Charge_ID() > 0 && ol.getC_Charge_ID() == ci.getC_ChargeFreight_ID()))
					continue;
				else if (ol.getM_Product_ID() > 0)
				{
					MProduct product = new MProduct(getCtx(), ol.getM_Product_ID(), get_TrxName());
					if (product.isService())
						continue;

					BigDecimal weight = product.getWeight();
					if (weight == null || weight.compareTo(BigDecimal.ZERO) == 0)
					{
						m_processMsg = "No weight defined for product " + product.toString();
						return false;
					}
				}
			}
			
			boolean ok = false;
			MShippingTransaction st = null;
			try
			{			
				st = SalesOrderRateInquiryProcess.createShippingTransaction(getCtx(), this, MShippingTransaction.ACTION_RateInquiry, isPriviledgedRate(), get_TrxName());
				ok = st.processOnline();
				st.saveEx();
			}
			catch (Exception e)
			{
				log.log(Level.SEVERE, "processOnline", e);
			}
			
			if (ok)
			{
				if (freightLine == null)
				{
					freightLine = new MOrderLine(this);
				
					if (ci.getC_ChargeFreight_ID() > 0)
						freightLine.setC_Charge_ID(ci.getC_ChargeFreight_ID());
					else if (ci.getM_ProductFreight_ID() > 0)
						freightLine.setM_Product_ID(ci.getM_ProductFreight_ID());
					else
						throw new AdempiereException("Product or Charge for Freight is not defined at Tenant window > Tenant Info tab");
				}
				
				freightLine.setC_BPartner_Location_ID(getC_BPartner_Location_ID());
				freightLine.setM_Shipper_ID(getM_Shipper_ID());
				freightLine.setQty(BigDecimal.ONE);
				freightLine.setPrice(st.getPrice());
				freightLine.saveEx();
			}
			else
			{
				m_processMsg = st.getErrorMessage();
				return false;
			}
		}
		
		return true;
	}
	
	/**
	 * 	Explode non stocked BOM (i.e IsBOM=Y and IsStocked=N).
	 * 	@return true if bom exploded
	 */
	protected boolean explodeBOM()
	{
		boolean retValue = false;
		String where = "AND IsActive='Y' AND EXISTS "
			+ "(SELECT * FROM M_Product p WHERE C_OrderLine.M_Product_ID=p.M_Product_ID"
			+ " AND	p.IsBOM='Y' AND p.IsVerified='Y' AND p.IsStocked='N')";
		//
		String sql = "SELECT COUNT(*) FROM C_OrderLine "
			+ "WHERE C_Order_ID=? " + where; 
		int count = DB.getSQLValue(get_TrxName(), sql, getC_Order_ID());
		while (count != 0)
		{
			retValue = true;
			renumberLines (1000);		//	max 999 bom items	

			//	Order Lines with non-stocked BOMs
			MOrderLine[] lines = getLines (where, MOrderLine.COLUMNNAME_Line);
			for (int i = 0; i < lines.length; i++)
			{
				MOrderLine line = lines[i];
				MProduct product = MProduct.get (getCtx(), line.getM_Product_ID());
				if (log.isLoggable(Level.FINE)) log.fine(product.getName());
				//	New Lines
				int lineNo = line.getLine ();
				MPPProductBOM bom = MPPProductBOM.getDefault(product, get_TrxName());
				if (bom == null)
					continue;
				for (MPPProductBOMLine bomLine : bom.getLines())
				{
					MOrderLine newLine = new MOrderLine(this);
					newLine.setLine(++lineNo);
					newLine.setM_Product_ID(bomLine.getM_Product_ID(), true);
					newLine.setQty(line.getQtyOrdered().multiply(bomLine.getQtyBOM()));
					if (bomLine.getDescription() != null)
						newLine.setDescription(bomLine.getDescription());
					newLine.setPrice();
					newLine.saveEx(get_TrxName());
				}
				
				//	Convert into Comment Line
				line.setM_Product_ID (0);
				line.setM_AttributeSetInstance_ID (0);
				line.setPrice (Env.ZERO);
				line.setPriceLimit (Env.ZERO);
				line.setPriceList (Env.ZERO);
				line.setLineNetAmt (Env.ZERO);
				line.setFreightAmt (Env.ZERO);
				//
				String description = product.getName ();
				if (product.getDescription () != null)
					description += " " + product.getDescription ();
				if (line.getDescription () != null)
					description += " " + line.getDescription ();
				line.setDescription (description);
				line.saveEx(get_TrxName());
			}	//	for all lines with BOM

			m_lines = null;		//	force requery
			count = DB.getSQLValue (get_TrxName(), sql, getC_Invoice_ID ());
			renumberLines (10);
		}	//	while count != 0
		return retValue;
	}	//	explodeBOM

	/**
	 * 	Reserve Inventory.
	 * 	Release of reservation: MInOut.completeIt().
	 * 	@param dt document type or null
	 * 	@param lines order lines (ordered by M_Product_ID for deadlock prevention)
	 * 	@return true if (un) reserved
	 */
	protected boolean reserveStock (MDocType dt, MOrderLine[] lines)
	{
		if (dt == null)
			dt = MDocType.get(getCtx(), getC_DocType_ID());

		//	Binding
		boolean binding = !dt.isProposal();
		//	Not binding - i.e. Target=0
		if (DOCACTION_Void.equals(getDocAction())
			//	Closing Binding Quotation
			|| (MDocType.DOCSUBTYPESO_Quotation.equals(dt.getDocSubTypeSO()) 
				&& DOCACTION_Close.equals(getDocAction())) 
			) // || isDropShip() )
			binding = false;
		boolean isSOTrx = isSOTrx();
		if (log.isLoggable(Level.FINE)) log.fine("Binding=" + binding + " - IsSOTrx=" + isSOTrx);
		//	Force same WH for all but SO/PO
		int header_M_Warehouse_ID = getM_Warehouse_ID();
		if (MDocType.DOCSUBTYPESO_StandardOrder.equals(dt.getDocSubTypeSO())
			|| MDocType.DOCBASETYPE_PurchaseOrder.equals(dt.getDocBaseType()))
			header_M_Warehouse_ID = 0;		//	don't enforce
		
		BigDecimal Volume = Env.ZERO;
		BigDecimal Weight = Env.ZERO;
		
		//	Always check and (un) Reserve Inventory		
		for (int i = 0; i < lines.length; i++)
		{
			MOrderLine line = lines[i];
			//	Check/set WH/Org
			if (header_M_Warehouse_ID != 0)	//	enforce WH
			{
				if (header_M_Warehouse_ID != line.getM_Warehouse_ID())
					line.setM_Warehouse_ID(header_M_Warehouse_ID);
				if (getAD_Org_ID() != line.getAD_Org_ID())
					line.setAD_Org_ID(getAD_Org_ID());
			}
			//	Binding
			BigDecimal target = binding ? line.getQtyOrdered() : Env.ZERO; 
			BigDecimal difference = target.compareTo(line.getQtyDelivered()) > 0 ? target.subtract(line.getQtyDelivered()) : Env.ZERO;
			difference = difference.subtract(line.getQtyReserved()); 

			if (difference.signum() == 0 || line.getQtyOrdered().signum() < 0)
			{
				if (difference.signum() == 0 || line.getQtyReserved().signum() == 0)
				{
					MProduct product = line.getProduct();
					if (product != null)
					{
						Volume = Volume.add(product.getVolume().multiply(line.getQtyOrdered()));
						Weight = Weight.add(product.getWeight().multiply(line.getQtyOrdered()));
					}
					continue;
				}
				else if (line.getQtyOrdered().signum() < 0 && line.getQtyReserved().signum() > 0)
				{
					difference = line.getQtyReserved().negate();
				}
			}
			
			if (log.isLoggable(Level.FINE)) log.fine("Line=" + line.getLine() 
				+ " - Target=" + target + ",Difference=" + difference
				+ " - Ordered=" + line.getQtyOrdered() 
				+ ",Reserved=" + line.getQtyReserved() + ",Delivered=" + line.getQtyDelivered());

			//	Check Product - Stocked and Item
			MProduct product = line.getProduct();
			if (product != null) 
			{
				if (product.isStocked())
				{
					IReservationTracer tracer = null;
					IReservationTracerFactory factory = Core.getReservationTracerFactory();
					if (factory != null) {
						tracer = factory.newTracer(getC_DocType_ID(), getDocumentNo(), line.getLine(), 
								line.get_Table_ID(), line.get_ID(), line.getM_Warehouse_ID(), 
								line.getM_Product_ID(), line.getM_AttributeSetInstance_ID(), isSOTrx(), 
								get_TrxName());
					}
					//	Update Reservation Storage
					if (!MStorageReservation.add(getCtx(), line.getM_Warehouse_ID(), 
						line.getM_Product_ID(), 
						line.getM_AttributeSetInstance_ID(),
						difference, isSOTrx, get_TrxName(), tracer))
						return false;
				}	//	stocked
				//	update line
				line.setQtyReserved(line.getQtyReserved().add(difference));
				if (!line.save(get_TrxName()))
					return false;
				//
				Volume = Volume.add(product.getVolume().multiply(line.getQtyOrdered()));
				Weight = Weight.add(product.getWeight().multiply(line.getQtyOrdered()));
			}	//	product
		}	//	reverse inventory
		
		setVolume(Volume);
		setWeight(Weight);
		return true;
	}	//	reserveStock

	/**
	 * 	Calculate Tax and Total (delete and re-create C_OrderTax records).
	 * 	@return true if no error
	 */
	public boolean calculateTaxTotal()
	{
		if (log.isLoggable(Level.FINE)) log.fine("");
		//	Delete Taxes
		DB.executeUpdateEx("DELETE FROM C_OrderTax WHERE C_Order_ID=" + getC_Order_ID(), get_TrxName());
		m_taxes = null;
		
		MTaxProvider[] providers = getTaxProviders();
		for (MTaxProvider provider : providers)
		{
			ITaxProvider calculator = Core.getTaxProvider(provider);
			if (calculator == null)
				throw new AdempiereException(Msg.getMsg(getCtx(), "TaxNoProvider"));
			
			if (!calculator.calculateOrderTaxTotal(provider, this))
				return false;
		}
		return true;
	}	//	calculateTaxTotal
		
	/**
	 * 	(Re) Create Pay Schedule
	 *	@return true if valid schedule
	 */
	protected boolean createPaySchedule()
	{
		if (getC_PaymentTerm_ID() == 0)
			return false;
		MPaymentTerm pt = new MPaymentTerm(getCtx(), getC_PaymentTerm_ID(), null);
		if (log.isLoggable(Level.FINE)) log.fine(pt.toString());

		int numSchema = pt.getSchedule(false).length;
		
		MOrderPaySchedule[] schedule = MOrderPaySchedule.getOrderPaySchedule
			(getCtx(), getC_Order_ID(), 0, get_TrxName());
		
		if (schedule.length > 0) {
			if (numSchema == 0)
				return false; // created a schedule for a payment term that doesn't manage schedule
			return validatePaySchedule();
		} else {
			boolean isValid = pt.applyOrder(this);		//	calls validate pay schedule
			if (numSchema == 0)
				return true; // no schedule, no schema, OK
			else
				return isValid;
		}
	}	//	createPaySchedule
		
	/**
	 * 	Approve Document
	 * 	@return true if success 
	 */
	@Override
	public boolean  approveIt()
	{
		if (log.isLoggable(Level.INFO)) log.info("approveIt - " + toString());
		setIsApproved(true);
		return true;
	}	//	approveIt
	
	/**
	 * 	Reject Approval
	 * 	@return true if success 
	 */
	@Override
	public boolean rejectIt()
	{
		if (log.isLoggable(Level.INFO)) log.info("rejectIt - " + toString());
		setIsApproved(false);
		return true;
	}	//	rejectIt
		
	/**
	 * 	Complete Document
	 * 	@return new status (Complete, In Progress, Invalid, Waiting ..)
	 */
	@Override
	public String completeIt()
	{
		MDocType dt = MDocType.get(getCtx(), getC_DocType_ID());
		String DocSubTypeSO = dt.getDocSubTypeSO();
		
		//	Just prepare
		if (DOCACTION_Prepare.equals(getDocAction()))
		{
			setProcessed(false);
			return DocAction.STATUS_InProgress;
		}

		// Set the definite document number after completed (if needed)
		setDefiniteDocumentNo();

		//	Offers
		if (MDocType.DOCSUBTYPESO_Proposal.equals(DocSubTypeSO)
			|| MDocType.DOCSUBTYPESO_Quotation.equals(DocSubTypeSO)) 
		{
			//	Binding
			if (MDocType.DOCSUBTYPESO_Quotation.equals(DocSubTypeSO))
				reserveStock(dt, getLines(true, MOrderLine.COLUMNNAME_M_Product_ID));
			m_processMsg = ModelValidationEngine.get().fireDocValidate(this, ModelValidator.TIMING_BEFORE_COMPLETE);
			if (m_processMsg != null)
				return DocAction.STATUS_Invalid;
			m_processMsg = ModelValidationEngine.get().fireDocValidate(this, ModelValidator.TIMING_AFTER_COMPLETE);
			if (m_processMsg != null)
				return DocAction.STATUS_Invalid;
			setProcessed(true);
			return DocAction.STATUS_Completed;
		}
		//	Waiting Payment - until we have a payment
		if (!m_forceCreation 
			&& MDocType.DOCSUBTYPESO_PrepayOrder.equals(DocSubTypeSO) 
			&& getC_Payment_ID() == 0 && getC_CashLine_ID() == 0)
		{
			setProcessed(true);
			return DocAction.STATUS_WaitingPayment;
		}
		
		//	Re-Check
		if (!m_justPrepared)
		{
			String status = prepareIt();
			m_justPrepared = false;
			if (!DocAction.STATUS_InProgress.equals(status))
				return status;
		}
		
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this, ModelValidator.TIMING_BEFORE_COMPLETE);
		if (m_processMsg != null)
			return DocAction.STATUS_Invalid;
		
		//	Implicit Approval
		if (!isApproved())
			approveIt();
		getLines(true,null);
		if (log.isLoggable(Level.INFO)) log.info(toString());
		StringBuilder info = new StringBuilder();
		
		boolean realTimePOS = MSysConfig.getBooleanValue(MSysConfig.REAL_TIME_POS, false , getAD_Client_ID());
		
		// Counter Documents
		// move by IDEMPIERE-2216
		MOrder counter = createCounterDoc();
		if (counter != null)
			info.append(" - @CounterDoc@: @Order@=").append(counter.getDocumentNo());
		
		//	Create SO Shipment - Force Shipment
		MInOut shipment = null;
		if (evalAutoGenerateInOutRule(DocSubTypeSO, dt.isAutoGenerateInout())) 
		{
			if (!DELIVERYRULE_Force.equals(getDeliveryRule()))
			{
				MWarehouse wh = new MWarehouse (getCtx(), getM_Warehouse_ID(), get_TrxName());
				if (!wh.isDisallowNegativeInv())
					setDeliveryRule(DELIVERYRULE_Force);
			}
			//
			shipment = createShipment (dt, realTimePOS ? null : getDateOrdered());
			if (shipment == null)
				return DocAction.STATUS_Invalid;
			info.append("@M_InOut_ID@: ").append(shipment.getDocumentNo());
			String msg = shipment.getProcessMsg();
			if (msg != null && msg.length() > 0)
				info.append(" (").append(msg).append(")");
		}	//	Shipment
		

		//	Create SO Invoice - Always invoice complete Order
		if ( MDocType.DOCSUBTYPESO_POSOrder.equals(DocSubTypeSO)
			|| MDocType.DOCSUBTYPESO_OnCreditOrder.equals(DocSubTypeSO) 	
			|| (MDocType.DOCSUBTYPESO_PrepayOrder.equals(DocSubTypeSO) && dt.isAutoGenerateInvoice())) 
		{
			MInvoice invoice = createInvoice (dt, shipment, realTimePOS ? null : getDateOrdered());
			if (invoice == null)
				return DocAction.STATUS_Invalid;
			info.append(" - @C_Invoice_ID@: ").append(invoice.getDocumentNo());
			String msg = invoice.getProcessMsg();
			if (msg != null && msg.length() > 0)
				info.append(" (").append(msg).append(")");
		}	//	Invoice
		
		String msg = createPOSPayments();
		if (msg != null) {
			m_processMsg = msg;
			return DocAction.STATUS_Invalid;
		}

		//	User Validation
		String valid = ModelValidationEngine.get().fireDocValidate(this, ModelValidator.TIMING_AFTER_COMPLETE);
		if (valid != null)
		{
			if (info.length() > 0)
				info.append(" - ");
			info.append(valid);
			m_processMsg = info.toString();
			return DocAction.STATUS_Invalid;
		}

		//landed cost
		if (!isSOTrx())
		{
			String error = landedCostAllocation();
			if (!Util.isEmpty(error))
			{
				m_processMsg = error;
				return DocAction.STATUS_Invalid;
			}
		}

		updateOverReceipt();
		
		setProcessed(true);	
		m_processMsg = info.toString();
		//
		setDocAction(DOCACTION_Close);
		return DocAction.STATUS_Completed;
	}	//	completeIt
	
	/**
	 * Evaluate if the order should auto generate shipment
	 * @param docSubTypeSO the document subtype of the order
	 * @param isAutoGenerateInout true if the document type is set to auto generate shipment
	 * @return true if the order should auto generate shipment
	 */
	private boolean evalAutoGenerateInOutRule(String docSubTypeSO, boolean isAutoGenerateInout) {
		return MDocType.DOCSUBTYPESO_OnCreditOrder.equals(docSubTypeSO)		//	(W)illCall(I)nvoice
				|| MDocType.DOCSUBTYPESO_WarehouseOrder.equals(docSubTypeSO)	//	(W)illCall(P)ickup	
				|| MDocType.DOCSUBTYPESO_POSOrder.equals(docSubTypeSO)			//	(W)alkIn(R)eceipt
				|| (MDocType.DOCSUBTYPESO_PrepayOrder.equals(docSubTypeSO) && isAutoGenerateInout);
	}
	
	/**
	 * Update QtyOverReceipt of M_InOutLine
	 */
	private void updateOverReceipt() {
		for(MOrderLine line : m_lines) {
			if (line.getM_Product_ID() <= 0) continue;
			if (line.getQtyDelivered().signum() > 0 && line.getQtyOrdered().compareTo(line.getQtyDelivered()) >= 0) {
				DB.executeUpdateEx("UPDATE M_InOutLine Set QtyOverReceipt=0 WHERE C_OrderLine_ID=? AND QtyOverReceipt>0", 
						new Object[] {line.getC_OrderLine_ID()}, get_TrxName());
			}
		}
	}
	
	/**
	 * distribute landed cost.
	 * @return error message or empty string
	 */
	protected String landedCostAllocation() {
		MOrderLandedCost[] landedCosts = MOrderLandedCost.getOfOrder(getC_Order_ID(), get_TrxName());
		for(MOrderLandedCost landedCost : landedCosts) {
			String error = landedCost.distributeLandedCost();
			if (!Util.isEmpty(error))
				return error;
		}
		return "";
	}

	/**
	 * @return error message or null
	 */
	protected String createPOSPayments() {

		// Just for POS order with payment rule mixed
		if (! this.isSOTrx())
			return null;
		if (! MOrder.DocSubTypeSO_POS.equals(this.getC_DocType().getDocSubTypeSO()))
			return null;
		if (! MOrder.PAYMENTRULE_MixedPOSPayment.equals(this.getPaymentRule()))
			return null;

		// Verify sum of all payments pos must be equal to the grandtotal of POS invoice (minus withholdings)
		MInvoice[] invoices = this.getInvoices();
		if (invoices == null || invoices.length == 0)
			return "@NoPOSInvoices@";
		MInvoice lastInvoice = invoices[0];
		BigDecimal grandTotal = lastInvoice.getGrandTotal();
		
		List<MPOSPayment> pps = new Query(this.getCtx(), MPOSPayment.Table_Name, "C_Order_ID=?", this.get_TrxName())
			.setParameters(this.getC_Order_ID())
			.setOnlyActiveRecords(true)
			.list();
		BigDecimal totalPOSPayments = Env.ZERO; 
		for (MPOSPayment pp : pps) {
			totalPOSPayments = totalPOSPayments.add(pp.getPayAmt());
		}
		if (totalPOSPayments.compareTo(grandTotal) != 0)
			return "@POSPaymentDiffers@ - @C_POSPayment_ID@=" + totalPOSPayments + ", @GrandTotal@=" + grandTotal;

		String whereClause = "AD_Org_ID=? AND C_Currency_ID=?";
		MBankAccount ba = new Query(this.getCtx(),MBankAccount.Table_Name,whereClause,this.get_TrxName())
			.setParameters(this.getAD_Org_ID(), this.getC_Currency_ID())
			.setOrderBy("IsDefault DESC")
			.first();
		if (ba == null)
			return "@NoAccountOrgCurrency@";
		
		MDocType[] doctypes = MDocType.getOfDocBaseType(this.getCtx(), MDocType.DOCBASETYPE_ARReceipt);
		if (doctypes == null || doctypes.length == 0)
			return "No document type for AR Receipt";
		MDocType doctype = null;
		for (MDocType doc : doctypes) {
			if (doc.getAD_Org_ID() == this.getAD_Org_ID()) {
				doctype = doc;
				break;
			}
		}
		if (doctype == null)
			doctype = doctypes[0];

		// Create a payment for each non-guarantee record
		// associate the payment id and mark the record as processed
		for (MPOSPayment pp : pps) {
			X_C_POSTenderType  tt = new X_C_POSTenderType (getCtx(),pp.getC_POSTenderType_ID(), get_TrxName());
			if (tt.isGuarantee())
				continue;
			if (pp.isPostDated())
				continue;

			MPayment payment = new MPayment(this.getCtx(), 0, this.get_TrxName());
			payment.setAD_Org_ID(this.getAD_Org_ID());

			payment.setTenderType(pp.getTenderType());
			if (MPayment.TENDERTYPE_CreditCard.equals(pp.getTenderType())) {
				payment.setTrxType(MPayment.TRXTYPE_Sales);
				payment.setCreditCardType(pp.getCreditCardType());
				payment.setCreditCardNumber(pp.getCreditCardNumber());
				payment.setVoiceAuthCode(pp.getVoiceAuthCode());
			}

			payment.setC_BankAccount_ID(ba.getC_BankAccount_ID());
			payment.setRoutingNo(pp.getRoutingNo());
			payment.setAccountNo(pp.getAccountNo());
			payment.setSwiftCode(pp.getSwiftCode());
			payment.setIBAN(pp.getIBAN());
			payment.setCheckNo(pp.getCheckNo());
			payment.setMicr(pp.getMicr());
			payment.setIsPrepayment(false);
			
			payment.setDateAcct(this.getDateAcct());
			payment.setDateTrx(this.getDateOrdered());
			//
			payment.setC_BPartner_ID(this.getC_BPartner_ID());
			payment.setC_Invoice_ID(lastInvoice.getC_Invoice_ID());
			// payment.setC_Order_ID(this.getC_Order_ID()); / do not set order to avoid the prepayment flag
			payment.setC_DocType_ID(doctype.getC_DocType_ID());
			payment.setC_Currency_ID(this.getC_Currency_ID());

			payment.setPayAmt(pp.getPayAmt());

			//	Copy statement line reference data
			payment.setA_Name(pp.getA_Name());
			
			payment.setC_POSTenderType_ID(pp.getC_POSTenderType_ID());
			
			//	Save payment
			payment.saveEx();

			pp.setC_Payment_ID(payment.getC_Payment_ID());
			pp.setProcessed(true);
			pp.saveEx();

			payment.setDocAction(MPayment.DOCACTION_Complete);
			if (!payment.processIt (MPayment.DOCACTION_Complete))
				return "Cannot Complete the Payment :" + payment;

			payment.saveEx();
		}

		return null;
	}
	
	/**
	 * 	Set the definite document number after completed
	 */
	protected void setDefiniteDocumentNo() {
		MDocType dt = MDocType.get(getCtx(), getC_DocType_ID());
		if (dt.isOverwriteDateOnComplete()) {
			/* a42niem - BF IDEMPIERE-63 - check if document has been completed before */ 
			if (this.getProcessedOn().signum() == 0) {
				setDateOrdered(TimeUtil.getDay(0));
				if (getDateAcct().before(getDateOrdered())) {
					setDateAcct(getDateOrdered());
					MPeriod.testPeriodOpen(getCtx(), getDateAcct(), getC_DocType_ID(), getAD_Org_ID());
				}
			}
		}
		if (dt.isOverwriteSeqOnComplete()) {
			/* a42niem - BF IDEMPIERE-63 - check if document has been completed before */ 
			if (this.getProcessedOn().signum() == 0) {
				String value = DB.getDocumentNo(getC_DocType_ID(), get_TrxName(), true, this);
				if (value != null)
					setDocumentNo(value);
			}
		}
	}

	/**
	 * 	Create Shipment
	 *	@param dt order document type
	 *	@param movementDate optional movement date (default today)
	 *	@return shipment or null
	 */
	protected MInOut createShipment(MDocType dt, Timestamp movementDate)
	{
		if (log.isLoggable(Level.INFO)) log.info("For " + dt);
		MInOut shipment = new MInOut (this, dt.getC_DocTypeShipment_ID(), movementDate);
		if (!shipment.save(get_TrxName()))
		{
			m_processMsg = "Could not create Shipment";
			return null;
		}
		//
		MOrderLine[] oLines = getLines(true, null);
		for (int i = 0; i < oLines.length; i++)
		{
			MOrderLine oLine = oLines[i];
			//
			MInOutLine ioLine = new MInOutLine(shipment);
			//	Qty = Ordered - Delivered
			BigDecimal MovementQty = oLine.getQtyOrdered().subtract(oLine.getQtyDelivered()); 
			if (MovementQty.signum() == 0 && getProcessedOn().signum() != 0) {
				// do not create lines with qty = 0 when the order is reactivated and completed again
				continue;
			}
			//	Location
			int M_Locator_ID = MStorageOnHand.getM_Locator_ID (oLine.getM_Warehouse_ID(), 
					oLine.getM_Product_ID(), oLine.getM_AttributeSetInstance_ID(), 
					MovementQty, get_TrxName());
			if (M_Locator_ID == 0)		//	Get default Location
			{
				MWarehouse wh = MWarehouse.get(getCtx(), oLine.getM_Warehouse_ID());
				M_Locator_ID = wh.getDefaultLocator().getM_Locator_ID();
			}
			//
			ioLine.setOrderLine(oLine, M_Locator_ID, MovementQty);
			ioLine.setQty(MovementQty);
			if (oLine.getQtyEntered().compareTo(oLine.getQtyOrdered()) != 0)
				ioLine.setQtyEntered(MovementQty
					.multiply(oLine.getQtyEntered())
					.divide(oLine.getQtyOrdered(), 6, RoundingMode.HALF_UP));
			if (!ioLine.save(get_TrxName()))
			{
				m_processMsg = "Could not create Shipment Line";
				return null;
			}
		}
		// added AdempiereException by zuhri
		if (!shipment.processIt(DocAction.ACTION_Complete))
			throw new AdempiereException(Msg.getMsg(getCtx(), "FailedProcessingDocument") + " - " + shipment.getProcessMsg());
		// end added
		shipment.saveEx(get_TrxName());
		if (!DOCSTATUS_Completed.equals(shipment.getDocStatus()))
		{
			m_processMsg = "@M_InOut_ID@: " + shipment.getProcessMsg();
			return null;
		}
		return shipment;
	}	//	createShipment

	/**
	 * 	Create Invoice
	 *	@param dt order document type
	 *	@param shipment optional shipment
	 *	@param invoiceDate invoice date
	 *	@return invoice or null
	 */
	protected MInvoice createInvoice (MDocType dt, MInOut shipment, Timestamp invoiceDate)
	{
		if (log.isLoggable(Level.INFO)) log.info(dt.toString());
		MInvoice invoice = new MInvoice (this, dt.getC_DocTypeInvoice_ID(), invoiceDate);
		if (!invoice.save(get_TrxName()))
		{
			m_processMsg = "Could not create Invoice";
			return null;
		}
		
		//	If we have a Shipment - use that as a base
		if (shipment != null)
		{
			if (!INVOICERULE_AfterDelivery.equals(getInvoiceRule()))
				setInvoiceRule(INVOICERULE_AfterDelivery);
			//
			MInOutLine[] sLines = shipment.getLines(false);
			for (int i = 0; i < sLines.length; i++)
			{
				MInOutLine sLine = sLines[i];
				//
				MInvoiceLine iLine = new MInvoiceLine(invoice);
				iLine.setShipLine(sLine);
				//	Qty = Delivered	
				if (sLine.sameOrderLineUOM())
					iLine.setQtyEntered(sLine.getQtyEntered());
				else
					iLine.setQtyEntered(sLine.getMovementQty());
				iLine.setQtyInvoiced(sLine.getMovementQty());
				if (!iLine.save(get_TrxName()))
				{
					m_processMsg = "Could not create Invoice Line from Shipment Line";
					return null;
				}
				//
				sLine.setIsInvoiced(true);
				if (!sLine.save(get_TrxName()))
				{
					log.warning("Could not update Shipment line: " + sLine);
				}
			}
		}
		else	//	Create Invoice from Order
		{
			if (!INVOICERULE_Immediate.equals(getInvoiceRule()))
				setInvoiceRule(INVOICERULE_Immediate);
			//
			MOrderLine[] oLines = getLines();
			for (int i = 0; i < oLines.length; i++)
			{
				MOrderLine oLine = oLines[i];
				//
				MInvoiceLine iLine = new MInvoiceLine(invoice);
				iLine.setOrderLine(oLine);
				//	Qty = Ordered - Invoiced	
				iLine.setQtyInvoiced(oLine.getQtyOrdered().subtract(oLine.getQtyInvoiced()));
				if (oLine.getQtyOrdered().compareTo(oLine.getQtyEntered()) == 0)
					iLine.setQtyEntered(iLine.getQtyInvoiced());
				else
					iLine.setQtyEntered(iLine.getQtyInvoiced().multiply(oLine.getQtyEntered())
						.divide(oLine.getQtyOrdered(), 12, RoundingMode.HALF_UP));
				if (!iLine.save(get_TrxName()))
				{
					m_processMsg = "Could not create Invoice Line from Order Line";
					return null;
				}
			}
		}
		
		// Copy payment schedule from order to invoice if any
		for (MOrderPaySchedule ops : MOrderPaySchedule.getOrderPaySchedule(getCtx(), getC_Order_ID(), 0, get_TrxName())) {
			MInvoicePaySchedule ips = new MInvoicePaySchedule(getCtx(), 0, get_TrxName());
			PO.copyValues(ops, ips);
			ips.setC_Invoice_ID(invoice.getC_Invoice_ID());
			ips.setAD_Org_ID(ops.getAD_Org_ID());
			ips.setProcessing(ops.isProcessing());
			ips.setIsActive(ops.isActive());
			if (!ips.save()) {
				m_processMsg = "ERROR: creating pay schedule for invoice from : "+ ops.toString();
				return null;
			}
		}
		
		// added AdempiereException by zuhri
		if (!invoice.processIt(DocAction.ACTION_Complete))
			throw new AdempiereException(Msg.getMsg(getCtx(), "FailedProcessingDocument") + " - " + invoice.getProcessMsg());
		// end added
		invoice.saveEx(get_TrxName());
		setC_CashLine_ID(invoice.getC_CashLine_ID());
		if (!DOCSTATUS_Completed.equals(invoice.getDocStatus()))
		{
			m_processMsg = "@C_Invoice_ID@: " + invoice.getProcessMsg();
			return null;
		}
		return invoice;
	}	//	createInvoice
	
	/**
	 * 	Create Counter Document
	 * 	@return counter order
	 */
	protected MOrder createCounterDoc()
	{
		//	Is this itself a counter doc ?
		if (getRef_Order_ID() != 0)
			return null;
		
		//	Org Must be linked to BPartner
		MOrg org = MOrg.get(getCtx(), getAD_Org_ID());
		int counterC_BPartner_ID = org.getLinkedC_BPartner_ID(get_TrxName()); 
		if (counterC_BPartner_ID == 0)
			return null;
		//	Business Partner needs to be linked to Org
		MBPartner bp = new MBPartner (getCtx(), getC_BPartner_ID(), get_TrxName());
		int counterAD_Org_ID = bp.getAD_OrgBP_ID(); 
		if (counterAD_Org_ID == 0)
			return null;
		
		MBPartner counterBP = new MBPartner (getCtx(), counterC_BPartner_ID, null);
		MOrgInfo counterOrgInfo = MOrgInfo.get(getCtx(), counterAD_Org_ID, get_TrxName());
		if (log.isLoggable(Level.INFO)) log.info("Counter BP=" + counterBP.getName());

		//	Document Type
		int C_DocTypeTarget_ID = 0;
		MDocTypeCounter counterDT = MDocTypeCounter.getCounterDocType(getCtx(), getC_DocType_ID());
		if (counterDT != null)
		{
			if (log.isLoggable(Level.FINE)) log.fine(counterDT.toString());
			if (!counterDT.isCreateCounter() || !counterDT.isValid())
				return null;
			C_DocTypeTarget_ID = counterDT.getCounter_C_DocType_ID();
		}
		else	//	indirect
		{
			C_DocTypeTarget_ID = MDocTypeCounter.getCounterDocType_ID(getCtx(), getC_DocType_ID());
			if (log.isLoggable(Level.FINE)) log.fine("Indirect C_DocTypeTarget_ID=" + C_DocTypeTarget_ID);
			if (C_DocTypeTarget_ID <= 0)
				return null;
		}
		//	Deep Copy
		MOrder counter = copyFrom (this, getDateOrdered(), 
			C_DocTypeTarget_ID, !isSOTrx(), true, false, get_TrxName());
		//
		counter.setAD_Org_ID(counterAD_Org_ID);
		counter.setM_Warehouse_ID(counterOrgInfo.getM_Warehouse_ID());
		counter.setDatePromised(getDatePromised());		// default is date ordered 
		//	References (Should not be required)
		counter.setSalesRep_ID(getSalesRep_ID());
		counter.saveEx(get_TrxName());
		
		//	Update copied lines
		MOrderLine[] counterLines = counter.getLines(true, null);
		for (int i = 0; i < counterLines.length; i++)
		{
			MOrderLine counterLine = counterLines[i];
			counterLine.setOrder(counter);	//	copies header values (BP, etc.)
			counterLine.setTax();
			counterLine.saveEx(get_TrxName());
		}
		if (log.isLoggable(Level.FINE)) log.fine(counter.toString());
		
		//	Document Action
		if (counterDT != null)
		{
			if (counterDT.getDocAction() != null)
			{
				counter.setDocAction(counterDT.getDocAction());
				// added AdempiereException by zuhri
				if (!counter.processIt(counterDT.getDocAction()))
					throw new AdempiereException(Msg.getMsg(getCtx(), "FailedProcessingDocument") + " - " + counter.getProcessMsg());
				// end added
				counter.saveEx(get_TrxName());
			}
		}
		return counter;
	}	//	createCounterDoc
	
	/**
	 * 	Void Document.
	 * 	Set Qtys to 0 - Sales: reverse all documents
	 * 	@return true if success 
	 */
	@Override
	public boolean voidIt()
	{
		if (log.isLoggable(Level.INFO)) log.info(toString());
		// Before Void
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_BEFORE_VOID);
		if (m_processMsg != null)
			return false;

		if (getLink_Order_ID() > 0) {
			MOrder so = new MOrder(getCtx(), getLink_Order_ID(), get_TrxName());
			so.setLink_Order_ID(0);
			so.saveEx();
		}

		if (isSOTrx()) {
			if (!createReversals())
				return false;
		} else {
			if (!createPOReversals())
				return false;
		}

		MOrderLine[] lines = getLines(true, MOrderLine.COLUMNNAME_M_Product_ID);
		for (int i = 0; i < lines.length; i++)
		{
			MOrderLine line = lines[i];
			BigDecimal old = line.getQtyOrdered();
			if (old.signum() != 0)
			{
				line.addDescription(Msg.getMsg(getCtx(), "Voided") + " (" + old + ")");
				line.setQty(Env.ZERO);
				line.setLineNetAmt(Env.ZERO);
				line.saveEx(get_TrxName());
			}
			if (!isSOTrx())
			{
				deleteMatchPOCostDetail(line);
			}
			if (line.getLink_OrderLine_ID() > 0) {
				MOrderLine soline = new MOrderLine(getCtx(), line.getLink_OrderLine_ID(), get_TrxName());
				soline.setLink_OrderLine_ID(0);
				soline.saveEx();
			}
		}
		
		// update taxes
		MOrderTax[] taxes = getTaxes(true);
		for (MOrderTax tax : taxes )
		{
			if ( !(tax.calculateTaxFromLines() && tax.save()) )
				return false;
		}
		
		addDescription(Msg.getMsg(getCtx(), "Voided"));
		//	Clear Reservations
		if (!reserveStock(null, lines))
		{
			m_processMsg = "Cannot unreserve Stock (void)";
			return false;
		}
		
		// UnLink All Requisitions
		MRequisitionLine.unlinkC_Order_ID(getCtx(), get_ID(), get_TrxName());
		
		/* globalqss - 2317928 - Reactivating/Voiding order must reset posted */
		MFactAcct.deleteEx(MOrder.Table_ID, getC_Order_ID(), get_TrxName());
		setPosted(false);
		
		// After Void
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_AFTER_VOID);
		if (m_processMsg != null)
			return false;
		
		setTotalLines(Env.ZERO);
		setGrandTotal(Env.ZERO);
		setProcessed(true);
		setDocAction(DOCACTION_None);
		return true;
	}	//	voidIt
	
	/**
	 * 	Create Shipment/Invoice Reversals
	 * 	@return true if success
	 */
	protected boolean createReversals()
	{
		//	Cancel only Sales 
		if (!isSOTrx())
			return true;
		
		if (log.isLoggable(Level.INFO))
			log.info("createReversals");
		StringBuilder info = new StringBuilder();
		
		//	Reverse All *Shipments*
		info.append("@M_InOut_ID@:");
		MInOut[] shipments = getShipments();
		for (int i = 0; i < shipments.length; i++)
		{
			MInOut ship = shipments[i];
			//	if closed - ignore
			if (MInOut.DOCSTATUS_Closed.equals(ship.getDocStatus())
				|| MInOut.DOCSTATUS_Reversed.equals(ship.getDocStatus())
				|| MInOut.DOCSTATUS_Voided.equals(ship.getDocStatus()) )
				continue;
			ship.set_TrxName(get_TrxName());
		
			//	If not completed - void - otherwise reverse it
			if (!MInOut.DOCSTATUS_Completed.equals(ship.getDocStatus()))
			{
				if (ship.voidIt())
					ship.setDocStatus(MInOut.DOCSTATUS_Voided);
			}
			else if (ship.reverseCorrectIt())	//	completed shipment
			{
				ship.setDocStatus(MInOut.DOCSTATUS_Reversed);
				info.append(" ").append(ship.getDocumentNo());
			}
			else
			{
				m_processMsg = "Could not reverse Shipment " + ship;
				return false;
			}
			ship.setDocAction(MInOut.DOCACTION_None);
			ship.saveEx(get_TrxName());
		}	//	for all shipments
			
		//	Reverse All *Invoices*
		info.append(" - @C_Invoice_ID@:");
		MInvoice[] invoices = getInvoices();
		for (int i = 0; i < invoices.length; i++)
		{
			MInvoice invoice = invoices[i];
			//	if closed - ignore
			if (MInvoice.DOCSTATUS_Closed.equals(invoice.getDocStatus())
				|| MInvoice.DOCSTATUS_Reversed.equals(invoice.getDocStatus())
				|| MInvoice.DOCSTATUS_Voided.equals(invoice.getDocStatus()) )
				continue;			
			invoice.set_TrxName(get_TrxName());
			
			//	If not completed - void - otherwise reverse it
			if (!MInvoice.DOCSTATUS_Completed.equals(invoice.getDocStatus()))
			{
				if (invoice.voidIt())
					invoice.setDocStatus(MInvoice.DOCSTATUS_Voided);
			}
			else if (invoice.reverseCorrectIt())	//	completed invoice
			{
				invoice.setDocStatus(MInvoice.DOCSTATUS_Reversed);
				info.append(" ").append(invoice.getDocumentNo());
			}
			else
			{
				m_processMsg = "Could not reverse Invoice " + invoice;
				return false;
			}
			invoice.setDocAction(MInvoice.DOCACTION_None);
			invoice.saveEx(get_TrxName());
		}	//	for all shipments
		
		m_processMsg = info.toString();
		return true;
	}	//	createReversals
	
	/**
	 * Create match po reversals
	 * @return true if success
	 */
	protected boolean createPOReversals() {
		if (isSOTrx())
			return true;
		
		Timestamp loginDate = TimeUtil.getDay(Env.getContextAsDate(Env.getCtx(), Env.DATE));
		for(MOrderLine line : getLines()) {
			MMatchPO[] matchPOs = MMatchPO.getOrderLine(Env.getCtx(), line.get_ID(), get_TrxName());
			for(MMatchPO matchPO : matchPOs) {
				if (matchPO.getReversal_ID() > 0)
					continue;
				if (!matchPO.reverse(loginDate, true)) {
					m_processMsg = "Could not Reverse " + matchPO;
					return false;
				}
				if (matchPO.getM_InOutLine_ID() > 0) {
					MInOutLine iol = new MInOutLine(Env.getCtx(), matchPO.getM_InOutLine_ID(), get_TrxName());
					MInOut io = new MInOut(Env.getCtx(), iol.getM_InOut_ID(), get_TrxName());
					if (io.getC_Order_ID() == this.getC_Order_ID()) {
						io.setC_Order_ID(0);
						io.saveEx();
					}
				}
			}
		}
		return true;
	}

	/**
	 * 	Close Document.
	 * 	Cancel not delivered Quantities.
	 * 	@return true if success 
	 */
	@Override
	public boolean closeIt()
	{
		if (log.isLoggable(Level.INFO)) log.info(toString());
		// Before Close
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_BEFORE_CLOSE);
		if (m_processMsg != null)
			return false;
		
		// Validate In Progress MInOUt
		StringBuilder sql = new StringBuilder("SELECT DISTINCT io.DocumentNo FROM M_InOut io ")
				.append("JOIN M_InOutLine iol ON (io.M_InOut_ID=iol.M_InOut_ID) ")
				.append("JOIN C_OrderLine ol ON (iol.C_OrderLine_ID=ol.C_OrderLine_ID) ")
				.append("WHERE io.DocStatus='IP' AND ol.QtyOrdered != 0 AND (ol.M_Product_ID > 0 OR ol.C_Charge_ID > 0) ")
				.append("AND ol.IsActive='Y' AND iol.IsActive='Y' ")
				.append("AND ol.C_Order_ID=? ");
		List<List<Object>> openShipments = DB.getSQLArrayObjectsEx(get_TrxName(), sql.toString(), getC_Order_ID());
		if (openShipments != null && openShipments.size() > 0) 
		{
			m_processMsg = Msg.getMsg(p_ctx,"MInOutInProgress")+" (";
			for(int i = 0; i< openShipments.size(); i++)
			{
				if (i > 0)
					m_processMsg += ", ";
				m_processMsg += openShipments.get(i).get(0).toString();
			}
			m_processMsg += ")";
			return false;
		}
		
		//	Close Not delivered Qty - SO/PO
		MOrderLine[] lines = getLines(true, MOrderLine.COLUMNNAME_M_Product_ID);
		for (int i = 0; i < lines.length; i++)
		{
			MOrderLine line = lines[i];
			BigDecimal old = line.getQtyOrdered();
			if (old.compareTo(line.getQtyDelivered()) != 0)
			{				
				if (line.getQtyOrdered().compareTo(line.getQtyDelivered()) > 0)
				{
					line.setQtyLostSales(line.getQtyOrdered().subtract(line.getQtyDelivered()));
					line.setQtyOrdered(line.getQtyDelivered());
				}
				else
				{
					line.setQtyLostSales(Env.ZERO);
				}
				//	QtyEntered unchanged
				line.addDescription("Close (" + old + ")");
				line.saveEx(get_TrxName());
			}
		}
		//	Clear Reservations
		if (!reserveStock(null, lines))
		{
			m_processMsg = "Cannot unreserve Stock (close)";
			return false;
		}
		
		setProcessed(true);
		setDocAction(DOCACTION_None);

		// IDEMPIERE-966 thanks to Hideaki Hagiwara
		if (!calculateTaxTotal()) {
			m_processMsg = Msg.getMsg(p_ctx,"Error calculating tax");
			return false;
		}

		// After Close
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_AFTER_CLOSE);
		if (m_processMsg != null)
			return false;
		return true;
	}	//	closeIt
	
	/**
	 * @author: phib
	 * re-open a closed order
	 * (reverse steps of close())
	 * @return error message or null
	 */
	public String reopenIt() {
		if (log.isLoggable(Level.INFO)) log.info(toString());
		if (!MOrder.DOCSTATUS_Closed.equals(getDocStatus()))
		{
			return "Not closed - can't reopen";
		}
		
		//	
		MOrderLine[] lines = getLines(true, MOrderLine.COLUMNNAME_M_Product_ID);
		for (int i = 0; i < lines.length; i++)
		{
			MOrderLine line = lines[i];
			if (Env.ZERO.compareTo(line.getQtyLostSales()) != 0)
			{
				line.setQtyOrdered(line.getQtyLostSales().add(line.getQtyDelivered()));
				line.setQtyLostSales(Env.ZERO);
				//	QtyEntered unchanged
				
				line.setDescription(line.getDescriptionStrippingCloseTag());
				if (!line.save(get_TrxName()))
					return "Couldn't save orderline";
			}
		}
		//	Clear Reservations
		if (!reserveStock(null, lines))
		{
			m_processMsg = "Cannot unreserve Stock (close)";
			return "Failed to update reservations";
		}

		setDocStatus(MOrder.DOCSTATUS_Completed);
		setDocAction(DOCACTION_Close);
		if (!this.save(get_TrxName()))
			return "Couldn't save reopened order";
		else
			return "";
	}	//	reopenIt
	
	/**
	 * 	Reverse Correction - same as void
	 * 	@return true if success 
	 */
	public boolean reverseCorrectIt()
	{
		if (log.isLoggable(Level.INFO)) log.info(toString());
		// Before reverseCorrect
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_BEFORE_REVERSECORRECT);
		if (m_processMsg != null)
			return false;
		
		// After reverseCorrect
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_AFTER_REVERSECORRECT);
		if (m_processMsg != null)
			return false;
		
		return voidIt();
	}	//	reverseCorrectionIt
	
	/**
	 * 	Reverse Accrual
	 * 	@return not implemented, always return false 
	 */
	public boolean reverseAccrualIt()
	{
		if (log.isLoggable(Level.INFO)) log.info(toString());
		// Before reverseAccrual
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_BEFORE_REVERSEACCRUAL);
		if (m_processMsg != null)
			return false;
		
		// After reverseAccrual
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_AFTER_REVERSEACCRUAL);
		if (m_processMsg != null)
			return false;
		
		return false;
	}	//	reverseAccrualIt
	
	/**
	 * 	Re-activate.
	 * 	@return true if success 
	 */
	@Override
	public boolean reActivateIt()
	{
		if (log.isLoggable(Level.INFO)) log.info(toString());
		// Before reActivate
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_BEFORE_REACTIVATE);
		if (m_processMsg != null)
			return false;	
		
		// Test period
		MPeriod.testPeriodOpen(getCtx(), getDateAcct(), getC_DocType_ID(), getAD_Org_ID());
		
		MDocType dt = MDocType.get(getCtx(), getC_DocType_ID());

		if (!DocumentEngine.canReactivateThisDocType(getC_DocType_ID())) {
			m_processMsg = Msg.getMsg(getCtx(), "DocTypeCannotBeReactivated", new Object[] {dt.getNameTrl()});
			return false;
		}

		String DocSubTypeSO = dt.getDocSubTypeSO();
		
		//	PO - just re-open
		if (!isSOTrx()) {
			if (log.isLoggable(Level.INFO)) log.info("Existing documents not modified - " + dt);
		//	Reverse Direct Documents
		} else if (MDocType.DOCSUBTYPESO_OnCreditOrder.equals(DocSubTypeSO)	//	(W)illCall(I)nvoice
			|| MDocType.DOCSUBTYPESO_WarehouseOrder.equals(DocSubTypeSO)	//	(W)illCall(P)ickup	
			|| MDocType.DOCSUBTYPESO_POSOrder.equals(DocSubTypeSO))			//	(W)alkIn(R)eceipt
		{
			if (!createReversals())
				return false;
		}
		else
		{
			if (log.isLoggable(Level.INFO)) log.info("Existing documents not modified - SubType=" + DocSubTypeSO);
		}

		/* globalqss - 2317928 - Reactivating/Voiding order must reset posted */
		MFactAcct.deleteEx(MOrder.Table_ID, getC_Order_ID(), get_TrxName());
		setPosted(false);
		
		// After reActivate
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_AFTER_REACTIVATE);
		if (m_processMsg != null)
			return false;
		
		setDocAction(DOCACTION_Complete);
		setProcessed(false);
		return true;
	}	//	reActivateIt
		
	/**
	 * 	Get Summary
	 *	@return Summary of Document
	 */
	@Override
	public String getSummary()
	{
		StringBuilder sb = new StringBuilder();
		sb.append(getDocumentNo());
		//	: Grand Total = 123.00 (#1)
		sb.append(": ").
			append(Msg.translate(getCtx(),"GrandTotal")).append("=").append(getGrandTotal());
		if (m_lines != null)
			sb.append(" (#").append(m_lines.length).append(")");
		//	 - Description
		if (getDescription() != null && getDescription().length() > 0)
			sb.append(" - ").append(getDescription());
		return sb.toString();
	}	//	getSummary
	
	/**
	 * 	Get Process Message
	 *	@return clear text error message
	 */
	@Override
	public String getProcessMsg()
	{
		return m_processMsg;
	}	//	getProcessMsg
	
	/**
	 * 	Get Document Owner (Responsible)
	 *	@return AD_User_ID
	 */
	@Override
	public int getDoc_User_ID()
	{
		return getSalesRep_ID();
	}	//	getDoc_User_ID

	/**
	 * 	Get Document Approval Amount
	 *	@return amount
	 */
	@Override
	public BigDecimal getApprovalAmt()
	{
		return getGrandTotal();
	}	//	getApprovalAmt
	
	/**
	 * Delete cost detail for order line
	 * @param line
	 * @return error message or null
	 */
	protected String deleteMatchPOCostDetail(MOrderLine line)
	{
		// Get Account Schemas to delete MCostDetail
		MAcctSchema[] acctschemas = MAcctSchema.getClientAcctSchema(getCtx(), getAD_Client_ID());
		for(int asn = 0; asn < acctschemas.length; asn++)
		{
			MAcctSchema as = acctschemas[asn];
			
			if (as.isSkipOrg(getAD_Org_ID()))
			{
				continue;
			}
			
			// update/delete Cost Detail and recalculate Current Cost
			MMatchPO[] mPO = MMatchPO.getOrderLine(getCtx(), line.getC_OrderLine_ID(), get_TrxName()); 
			// delete Cost Detail if the Matched PO has been deleted
			if (mPO.length == 0)
			{
				List<MCostDetail> cds = MCostDetail.list(Env.getCtx(), "C_OrderLine_ID=?", 
						line.getC_OrderLine_ID(), 0, as.get_ID(), get_TrxName());
				for (MCostDetail cd : cds)
				{
					cd.setProcessed(false);
					cd.delete(true);
				}
			}
		}
		
		return "";
	}

	/**
	 * 	Document Status is Complete or Closed
	 *	@return true if CO, CL or RE
	 */
	public boolean isComplete()
	{
		String ds = getDocStatus();
		return DOCSTATUS_Completed.equals(ds) 
			|| DOCSTATUS_Closed.equals(ds)
			|| DOCSTATUS_Reversed.equals(ds);
	}	//	isComplete

	/**
	 * Set process message
	 * @param processMsg
	 */
	public void setProcessMessage(String processMsg)
	{
		m_processMsg = processMsg;
	}
	
	/**
	 * Get tax providers
	 * @return array of tax provider
	 */
	public MTaxProvider[] getTaxProviders()
	{
		Hashtable<Integer, MTaxProvider> providers = new Hashtable<Integer, MTaxProvider>();
		MOrderLine[] lines = getLines();
		for (MOrderLine line : lines)
		{
			if (line.isDescription())
				continue;
            MTax tax = new MTax(line.getCtx(), line.getC_Tax_ID(), line.get_TrxName());
            MTaxProvider provider = providers.get(tax.getC_TaxProvider_ID());
            if (provider == null)
            	providers.put(tax.getC_TaxProvider_ID(), new MTaxProvider(tax.getCtx(), tax.getC_TaxProvider_ID(), tax.get_TrxName()));
		}
		
		MTaxProvider[] retValue = new MTaxProvider[providers.size()];
		providers.values().toArray(retValue);
		return retValue;
	}

	/** Returns C_DocType_ID (or C_DocTypeTarget_ID if C_DocType_ID is not set) */
	public int getDocTypeID()
	{
		return getC_DocType_ID() > 0 ? getC_DocType_ID() : getC_DocTypeTarget_ID();
	}

	/**
	 * 
	 * @return payment amount for order (prepayment + invoice payment)
	 */
	public BigDecimal getPaymentAmt()
	{
		BigDecimal orderPaid = null;
		String sql = "SELECT SUM(currencyconvertpayment(p.c_payment_id, o.c_currency_id, p.PayAmt+p.DiscountAmt+p.WriteOffAmt, null) "
				+ " - paymentallocated(p.c_payment_id, o.c_currency_id) "
				+ " * (CASE WHEN p.IsReceipt='Y' THEN 1 ELSE -1 END)) "
				+ "FROM C_Payment p "
				+ "INNER JOIN C_Order o ON (p.C_Order_ID=o.C_Order_ID) "
				+ "WHERE p.C_Order_ID=? AND p.AD_Client_ID=? "
				+ "AND p.IsActive='Y' AND p.DocStatus IN ('CO','CL') ";
				
		try (PreparedStatement pstmt = DB.prepareStatement(sql, get_TrxName());)
		{			
			pstmt.setInt(1, getC_Order_ID());
			pstmt.setInt(2, getAD_Client_ID());
			ResultSet rs = pstmt.executeQuery();
			if (rs.next())
			{
				orderPaid = rs.getBigDecimal(1);
			}
		}
		catch (SQLException e)
		{
			throw new DBException(e, sql);
		}
		
		BigDecimal invoicePaid = null;
		sql = "SELECT SUM(invoicepaid(i.c_invoice_id, o.c_currency_id, 1)) "
				+ "FROM C_Invoice i "
				+ "INNER JOIN C_Order o ON (i.C_Order_ID=o.C_Order_ID) "
				+ "WHERE i.C_Order_ID=? AND i.AD_Client_ID=? "
				+ "AND i.IsActive='Y' AND i.DocStatus IN ('CO','CL') ";
				
		try (PreparedStatement pstmt = DB.prepareStatement(sql, get_TrxName());)
		{			
			pstmt.setInt(1, getC_Order_ID());
			pstmt.setInt(2, getAD_Client_ID());
			ResultSet rs = pstmt.executeQuery();
			if (rs.next())
			{
				invoicePaid = rs.getBigDecimal(1);
			}
		}
		catch (SQLException e)
		{
			throw new DBException(e, sql);
		}
		
		BigDecimal retValue = orderPaid != null ? orderPaid : BigDecimal.ZERO;
		if (invoicePaid != null)
			retValue = retValue.add(invoicePaid);
		return retValue;
	}
}	//	MOrder
