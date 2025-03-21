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

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.Properties;
import org.compiere.util.Env;
import org.compiere.util.KeyNamePair;

/** Generated Model for C_SalesStage
 *  @author iDempiere (generated)
 *  @version Release 12 - $Id$ */
@org.adempiere.base.Model(table="C_SalesStage")
public class X_C_SalesStage extends PO implements I_C_SalesStage, I_Persistent
{

	/**
	 *
	 */
	private static final long serialVersionUID = 20241222L;

    /** Standard Constructor */
    public X_C_SalesStage (Properties ctx, int C_SalesStage_ID, String trxName)
    {
      super (ctx, C_SalesStage_ID, trxName);
      /** if (C_SalesStage_ID == 0)
        {
			setC_SalesStage_ID (0);
			setIsClosed (false);
// N
			setName (null);
			setProbability (Env.ZERO);
			setValue (null);
        } */
    }

    /** Standard Constructor */
    public X_C_SalesStage (Properties ctx, int C_SalesStage_ID, String trxName, String ... virtualColumns)
    {
      super (ctx, C_SalesStage_ID, trxName, virtualColumns);
      /** if (C_SalesStage_ID == 0)
        {
			setC_SalesStage_ID (0);
			setIsClosed (false);
// N
			setName (null);
			setProbability (Env.ZERO);
			setValue (null);
        } */
    }

    /** Standard Constructor */
    public X_C_SalesStage (Properties ctx, String C_SalesStage_UU, String trxName)
    {
      super (ctx, C_SalesStage_UU, trxName);
      /** if (C_SalesStage_UU == null)
        {
			setC_SalesStage_ID (0);
			setIsClosed (false);
// N
			setName (null);
			setProbability (Env.ZERO);
			setValue (null);
        } */
    }

    /** Standard Constructor */
    public X_C_SalesStage (Properties ctx, String C_SalesStage_UU, String trxName, String ... virtualColumns)
    {
      super (ctx, C_SalesStage_UU, trxName, virtualColumns);
      /** if (C_SalesStage_UU == null)
        {
			setC_SalesStage_ID (0);
			setIsClosed (false);
// N
			setName (null);
			setProbability (Env.ZERO);
			setValue (null);
        } */
    }

    /** Load Constructor */
    public X_C_SalesStage (Properties ctx, ResultSet rs, String trxName)
    {
      super (ctx, rs, trxName);
    }

    /** AccessLevel
      * @return 3 - Client - Org
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
      StringBuilder sb = new StringBuilder ("X_C_SalesStage[")
        .append(get_ID()).append(",Name=").append(getName()).append("]");
      return sb.toString();
    }

	/** Set Sales Stage.
		@param C_SalesStage_ID Stages of the sales process
	*/
	public void setC_SalesStage_ID (int C_SalesStage_ID)
	{
		if (C_SalesStage_ID < 1)
			set_ValueNoCheck (COLUMNNAME_C_SalesStage_ID, null);
		else
			set_ValueNoCheck (COLUMNNAME_C_SalesStage_ID, Integer.valueOf(C_SalesStage_ID));
	}

	/** Get Sales Stage.
		@return Stages of the sales process
	  */
	public int getC_SalesStage_ID()
	{
		Integer ii = (Integer)get_Value(COLUMNNAME_C_SalesStage_ID);
		if (ii == null)
			 return 0;
		return ii.intValue();
	}

	/** Set C_SalesStage_UU.
		@param C_SalesStage_UU C_SalesStage_UU
	*/
	public void setC_SalesStage_UU (String C_SalesStage_UU)
	{
		set_Value (COLUMNNAME_C_SalesStage_UU, C_SalesStage_UU);
	}

	/** Get C_SalesStage_UU.
		@return C_SalesStage_UU	  */
	public String getC_SalesStage_UU()
	{
		return (String)get_Value(COLUMNNAME_C_SalesStage_UU);
	}

	/** Set Description.
		@param Description Optional short description of the record
	*/
	public void setDescription (String Description)
	{
		set_Value (COLUMNNAME_Description, Description);
	}

	/** Get Description.
		@return Optional short description of the record
	  */
	public String getDescription()
	{
		return (String)get_Value(COLUMNNAME_Description);
	}

	/** Set Closed Status.
		@param IsClosed The status is closed
	*/
	public void setIsClosed (boolean IsClosed)
	{
		set_Value (COLUMNNAME_IsClosed, Boolean.valueOf(IsClosed));
	}

	/** Get Closed Status.
		@return The status is closed
	  */
	public boolean isClosed()
	{
		Object oo = get_Value(COLUMNNAME_IsClosed);
		if (oo != null)
		{
			 if (oo instanceof Boolean)
				 return ((Boolean)oo).booleanValue();
			return "Y".equals(oo);
		}
		return false;
	}

	/** Set Won.
		@param IsWon The opportunity was won
	*/
	public void setIsWon (boolean IsWon)
	{
		set_Value (COLUMNNAME_IsWon, Boolean.valueOf(IsWon));
	}

	/** Get Won.
		@return The opportunity was won
	  */
	public boolean isWon()
	{
		Object oo = get_Value(COLUMNNAME_IsWon);
		if (oo != null)
		{
			 if (oo instanceof Boolean)
				 return ((Boolean)oo).booleanValue();
			return "Y".equals(oo);
		}
		return false;
	}

	/** Set Name.
		@param Name Alphanumeric identifier of the entity
	*/
	public void setName (String Name)
	{
		set_Value (COLUMNNAME_Name, Name);
	}

	/** Get Name.
		@return Alphanumeric identifier of the entity
	  */
	public String getName()
	{
		return (String)get_Value(COLUMNNAME_Name);
	}

    /** Get Record ID/ColumnName
        @return ID/ColumnName pair
      */
    public KeyNamePair getKeyNamePair()
    {
        return new KeyNamePair(get_ID(), getName());
    }

	/** Set Probability.
		@param Probability Probability
	*/
	public void setProbability (BigDecimal Probability)
	{
		set_Value (COLUMNNAME_Probability, Probability);
	}

	/** Get Probability.
		@return Probability	  */
	public BigDecimal getProbability()
	{
		BigDecimal bd = (BigDecimal)get_Value(COLUMNNAME_Probability);
		if (bd == null)
			 return Env.ZERO;
		return bd;
	}

	/** Set Search Key.
		@param Value Search key for the record in the format required - must be unique
	*/
	public void setValue (String Value)
	{
		set_Value (COLUMNNAME_Value, Value);
	}

	/** Get Search Key.
		@return Search key for the record in the format required - must be unique
	  */
	public String getValue()
	{
		return (String)get_Value(COLUMNNAME_Value);
	}
}