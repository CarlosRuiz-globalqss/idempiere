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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Properties;
import java.util.logging.Level;

import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Util;
import org.idempiere.cache.ImmutableIntPOCache;
import org.idempiere.cache.ImmutablePOSupport;

/**
 *  Interest Area.
 *
 *  @author Jorg Janke
 *  @version $Id: MInterestArea.java,v 1.3 2006/07/30 00:51:05 jjanke Exp $
 */ 
public class MInterestArea extends X_R_InterestArea implements ImmutablePOSupport
{
	/**
	 * generated serial id
	 */
	private static final long serialVersionUID = -8171678779149295978L;

	/**
	 * 	Get all active interest areas
	 *	@param ctx context
	 *	@return interest areas
	 */
	public static MInterestArea[] getAll (Properties ctx)
	{
		ArrayList<MInterestArea> list = new ArrayList<MInterestArea>();
		String sql = "SELECT * FROM R_InterestArea WHERE IsActive='Y'";
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try
		{
			pstmt = DB.prepareStatement (sql, null);
			rs = pstmt.executeQuery ();
			while (rs.next ())
			{
				MInterestArea ia = new MInterestArea (ctx, rs, null);
				list.add (ia);
			}
		}
		catch (Exception e)
		{
			s_log.log(Level.SEVERE, sql, e);
		}
		finally
		{
			DB.close(rs, pstmt);
			rs = null;
			pstmt = null;
		}
		MInterestArea[] retValue = new MInterestArea[list.size ()];
		list.toArray (retValue);
		return retValue;
	}	//	getAll

	/**
	 * 	Get MInterestArea from Cache (immutable)
	 *	@param R_InterestArea_ID id
	 *	@return MInterestArea
	 */
	public static MInterestArea get (int R_InterestArea_ID)
	{
		return get(Env.getCtx(), R_InterestArea_ID);
	}
	
	/**
	 * 	Get MInterestArea from Cache (immutable)
	 *  @param ctx context
	 *	@param R_InterestArea_ID id
	 *	@return MInterestArea
	 */
	public static MInterestArea get (Properties ctx, int R_InterestArea_ID)
	{
		Integer key = Integer.valueOf(R_InterestArea_ID);
		MInterestArea retValue = s_cache.get (ctx, key, e -> new MInterestArea(ctx, e));
		if (retValue != null)
			return retValue;
		retValue = new MInterestArea (Env.getCtx(), R_InterestArea_ID, (String)null);
		if (retValue.get_ID () == R_InterestArea_ID)
		{
			s_cache.put (key, retValue, e -> new MInterestArea(Env.getCtx(), e));
			return retValue;
		}
		return null;
	} //	get

	/**	Cache						*/
	private static ImmutableIntPOCache<Integer,MInterestArea> s_cache = 
		new ImmutableIntPOCache<Integer,MInterestArea>(Table_Name, 5);
	/**	Logger	*/
	private static CLogger s_log = CLogger.getCLogger (MInterestArea.class);
		
    /**
     * UUID based Constructor
     * @param ctx  Context
     * @param R_InterestArea_UU  UUID key
     * @param trxName Transaction
     */
    public MInterestArea(Properties ctx, String R_InterestArea_UU, String trxName) {
        super(ctx, R_InterestArea_UU, trxName);
		if (Util.isEmpty(R_InterestArea_UU))
			setInitialDefaults();
    }

	/**
	 * 	Constructor
	 *	@param ctx context
	 *	@param R_InterestArea_ID interest area
	 *	@param trxName transaction
	 */
	public MInterestArea (Properties ctx, int R_InterestArea_ID, String trxName)
	{
		super (ctx, R_InterestArea_ID, trxName);
		if (R_InterestArea_ID == 0)
			setInitialDefaults();
	}	//	MInterestArea

	/**
	 * Set the initial defaults for a new record
	 */
	private void setInitialDefaults() {
		setIsSelfService (false);
	}

	/**
	 * 	Loader Constructor
	 *	@param ctx context
	 *	@param rs result set
	 *	@param trxName transaction
	 */
	public MInterestArea (Properties ctx, ResultSet rs, String trxName)
	{
		super(ctx, rs, trxName);
	}	//	MInterestArea

	/**
	 * Copy constructor
	 * @param copy
	 */
	public MInterestArea(MInterestArea copy) 
	{
		this(Env.getCtx(), copy);
	}

	/**
	 * Copy constructor
	 * @param ctx
	 * @param copy
	 */
	public MInterestArea(Properties ctx, MInterestArea copy) 
	{
		this(ctx, copy, (String) null);
	}

	/**
	 * Copy constructor
	 * @param ctx
	 * @param copy
	 * @param trxName
	 */
	public MInterestArea(Properties ctx, MInterestArea copy, String trxName) 
	{
		this(ctx, 0, trxName);
		copyPO(copy);
		this.m_AD_User_ID = copy.m_AD_User_ID;
		this.m_ci = copy.m_ci != null ? new MContactInterest(ctx, copy.m_ci, trxName) : null;
	}

	/**
	 * 	Get Value
	 *	@return value or name (if value is empty/null)
	 */
	@Override
	public String getValue()
	{
		String s = super.getValue ();
		if (s != null && s.length () > 0)
			return s;
		return super.getName();
	}	//	getValue

	/**
	 * 	String representation
	 * 	@return info
	 */
	@Override
	public String toString ()
	{
		StringBuilder sb = new StringBuilder ("MInterestArea[")
			.append (get_ID()).append(" - ").append(getName())
			.append ("]");
		return sb.toString ();
	}	//	toString

	private int 				m_AD_User_ID = -1;
	private MContactInterest 	m_ci = null;

	/**
	 * 	Set Subscription info. Create inactive MContactInterest if no existing MContactInterest for contact.
	 *	@param AD_User_ID contact
	 */
	public void setSubscriptionInfo (int AD_User_ID)
	{
		m_AD_User_ID = AD_User_ID;
		m_ci = MContactInterest.get(getCtx(), getR_InterestArea_ID(), AD_User_ID, 
			false, get_TrxName());
	}	//	setSubscription

	/**
	 * 	Set AD_User_ID
	 *	@param AD_User_ID user
	 */
	public void setAD_User_ID (int AD_User_ID)
	{
		m_AD_User_ID = AD_User_ID;
	}

	/**
	 * 	Get AD_User_ID
	 *	@return user
	 */
	public int getAD_User_ID ()
	{
		return m_AD_User_ID;
	}

	/**
	 * 	Get Subscribe Date
	 *	@return subscribe date
	 */
	public Timestamp getSubscribeDate ()
	{
		if (m_ci != null)
			return m_ci.getSubscribeDate();
		return null;
	}

	/**
	 * 	Get Opt Out Date
	 *	@return opt-out date
	 */
	public Timestamp getOptOutDate ()
	{
		if (m_ci != null)
			return m_ci.getOptOutDate();
		return null;
	}

	/**
	 * 	Is Subscribed
	 *	@return true if sunscribed
	 */
	public boolean isSubscribed()
	{
		if (m_AD_User_ID <= 0 || m_ci == null)
			return false;
		//	We have a BPartner Contact
		return m_ci.isSubscribed();
	}	//	isSubscribed

	@Override
	public MInterestArea markImmutable() {
		if (is_Immutable())
			return this;

		makeImmutable();
		return this;
	}

}	//	MInterestArea
