/******************************************************************************
 * Product: iDempiere ERP & CRM Smart Business Solution                       *
 * Copyright (C) 1999-2012 ComPiere, Inc. All Rights Reserved.                *
 * This program is free software, you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY, without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program, if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 * For the text or an alternative of this public license, you may reach us    *
 * ComPiere, Inc., 2620 Augustine Dr. #245, Santa Clara, CA 95054, USA        *
 * or via info@compiere.org or http://www.compiere.org/license.html           *
 *****************************************************************************/
/** Generated Model - DO NOT CHANGE */
package org.compiere.model;

import java.sql.ResultSet;
import java.util.Properties;

/** Generated Model for AD_Sequence_No
 *  @author iDempiere (generated)
 *  @version Release 13 - $Id$ */
@org.adempiere.base.Model(table="AD_Sequence_No")
public class X_AD_Sequence_No extends PO implements I_AD_Sequence_No, I_Persistent
{

	/**
	 *
	 */
	private static final long serialVersionUID = 20250612L;

    /** Standard Constructor */
    public X_AD_Sequence_No (Properties ctx, int AD_Sequence_No_ID, String trxName)
    {
      super (ctx, AD_Sequence_No_ID, trxName);
      /** if (AD_Sequence_No_ID == 0)
        {
			setAD_Sequence_ID (0);
			setCurrentNext (0);
			setSequenceKey (null);
        } */
    }

    /** Standard Constructor */
    public X_AD_Sequence_No (Properties ctx, int AD_Sequence_No_ID, String trxName, String ... virtualColumns)
    {
      super (ctx, AD_Sequence_No_ID, trxName, virtualColumns);
      /** if (AD_Sequence_No_ID == 0)
        {
			setAD_Sequence_ID (0);
			setCurrentNext (0);
			setSequenceKey (null);
        } */
    }

    /** Standard Constructor */
    public X_AD_Sequence_No (Properties ctx, String AD_Sequence_No_UU, String trxName)
    {
      super (ctx, AD_Sequence_No_UU, trxName);
      /** if (AD_Sequence_No_UU == null)
        {
			setAD_Sequence_ID (0);
			setCurrentNext (0);
			setSequenceKey (null);
        } */
    }

    /** Standard Constructor */
    public X_AD_Sequence_No (Properties ctx, String AD_Sequence_No_UU, String trxName, String ... virtualColumns)
    {
      super (ctx, AD_Sequence_No_UU, trxName, virtualColumns);
      /** if (AD_Sequence_No_UU == null)
        {
			setAD_Sequence_ID (0);
			setCurrentNext (0);
			setSequenceKey (null);
        } */
    }

    /** Load Constructor */
    public X_AD_Sequence_No (Properties ctx, ResultSet rs, String trxName)
    {
      super (ctx, rs, trxName);
    }

    /** AccessLevel
      * @return 6 - System - Client
      */
    protected int get_AccessLevel()
    {
      return accessLevel.intValue();
    }

    /** Load Meta Data */
    protected POInfo initPO (Properties ctx)
    {
      POInfo poi = POInfo.getPOInfo (ctx, Table_ID, get_TrxName());
      return poi;
    }

    public String toString()
    {
      StringBuilder sb = new StringBuilder ("X_AD_Sequence_No[")
        .append(get_UUID()).append("]");
      return sb.toString();
    }

	public org.compiere.model.I_AD_Sequence getAD_Sequence() throws RuntimeException
	{
		return (org.compiere.model.I_AD_Sequence)MTable.get(getCtx(), org.compiere.model.I_AD_Sequence.Table_ID)
			.getPO(getAD_Sequence_ID(), get_TrxName());
	}

	/** Set Sequence.
		@param AD_Sequence_ID Document Sequence
	*/
	public void setAD_Sequence_ID (int AD_Sequence_ID)
	{
		if (AD_Sequence_ID < 1)
			set_ValueNoCheck (COLUMNNAME_AD_Sequence_ID, null);
		else
			set_ValueNoCheck (COLUMNNAME_AD_Sequence_ID, Integer.valueOf(AD_Sequence_ID));
	}

	/** Get Sequence.
		@return Document Sequence
	  */
	public int getAD_Sequence_ID()
	{
		Integer ii = (Integer)get_Value(COLUMNNAME_AD_Sequence_ID);
		if (ii == null)
			 return 0;
		return ii.intValue();
	}

	/** Set AD_Sequence_No_UU.
		@param AD_Sequence_No_UU AD_Sequence_No_UU
	*/
	public void setAD_Sequence_No_UU (String AD_Sequence_No_UU)
	{
		set_Value (COLUMNNAME_AD_Sequence_No_UU, AD_Sequence_No_UU);
	}

	/** Get AD_Sequence_No_UU.
		@return AD_Sequence_No_UU	  */
	public String getAD_Sequence_No_UU()
	{
		return (String)get_Value(COLUMNNAME_AD_Sequence_No_UU);
	}

	/** Set Current Next.
		@param CurrentNext The next number to be used
	*/
	public void setCurrentNext (int CurrentNext)
	{
		set_Value (COLUMNNAME_CurrentNext, Integer.valueOf(CurrentNext));
	}

	/** Get Current Next.
		@return The next number to be used
	  */
	public int getCurrentNext()
	{
		Integer ii = (Integer)get_Value(COLUMNNAME_CurrentNext);
		if (ii == null)
			 return 0;
		return ii.intValue();
	}

	/** Set Sequence Key.
		@param SequenceKey Stores a unique key that determines the sequence numbering scope.
	*/
	public void setSequenceKey (String SequenceKey)
	{
		set_ValueNoCheck (COLUMNNAME_SequenceKey, SequenceKey);
	}

	/** Get Sequence Key.
		@return Stores a unique key that determines the sequence numbering scope.
	  */
	public String getSequenceKey()
	{
		return (String)get_Value(COLUMNNAME_SequenceKey);
	}
}