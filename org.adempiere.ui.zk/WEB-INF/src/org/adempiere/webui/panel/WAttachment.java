/******************************************************************************
 * Copyright (C) 2008 Low Heng Sin  All Rights Reserved.                      *
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
 * Posterita Ltd., 3, Draper Avenue, Quatre Bornes, Mauritius                 *
 * or via info@posterita.org or http://www.posterita.org/                     *
 *****************************************************************************/

package org.adempiere.webui.panel;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import javax.activation.FileDataSource;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.util.Callback;
import org.adempiere.webui.AdempiereWebUI;
import org.adempiere.webui.ClientInfo;
import org.adempiere.webui.Extensions;
import org.adempiere.webui.apps.AEnv;
import org.adempiere.webui.component.Button;
import org.adempiere.webui.component.ConfirmPanel;
import org.adempiere.webui.component.Label;
import org.adempiere.webui.component.ListItem;
import org.adempiere.webui.component.Listbox;
import org.adempiere.webui.component.Panel;
import org.adempiere.webui.component.Textbox;
import org.adempiere.webui.component.ToolBar;
import org.adempiere.webui.component.Window;
import org.adempiere.webui.event.DialogEvents;
import org.adempiere.webui.factory.ButtonFactory;
import org.adempiere.webui.session.SessionManager;
import org.adempiere.webui.theme.ThemeManager;
import org.adempiere.webui.util.Icon;
import org.adempiere.webui.util.ZKUpdateUtil;
import org.adempiere.webui.window.Dialog;
import org.adempiere.webui.window.WEMailDialog;
import org.adempiere.webui.window.WTextEditorDialog;
import org.compiere.model.MAttachment;
import org.compiere.model.MAttachmentEntry;
import org.compiere.model.MSysConfig;
import org.compiere.model.MTable;
import org.compiere.model.MUser;
import org.compiere.util.CLogger;
import org.compiere.util.Env;
import org.compiere.util.Msg;
import org.compiere.util.Util;
import org.idempiere.ui.zk.media.IMediaView;
import org.idempiere.ui.zk.media.Medias;
import org.zkoss.io.RepeatableInputStream;
import org.zkoss.util.media.AMedia;
import org.zkoss.util.media.Media;
import org.zkoss.zk.au.out.AuEcho;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zk.ui.event.UploadEvent;
import org.zkoss.zk.ui.ext.render.DynamicMedia;
import org.zkoss.zk.ui.util.Clients;
import org.zkoss.zul.Borderlayout;
import org.zkoss.zul.Center;
import org.zkoss.zul.Filedownload;
import org.zkoss.zul.Hbox;
import org.zkoss.zul.Hlayout;
import org.zkoss.zul.Iframe;
import org.zkoss.zul.North;
import org.zkoss.zul.South;
import org.zkoss.zul.Vlayout;
import org.zkoss.zul.impl.Utils;
import org.zkoss.zul.impl.XulElement;

/**
 * Attachment window
 * @author Low Heng Sin
 *
 */
public class WAttachment extends Window implements EventListener<Event>
{
	/**
	 * 
	 */
	private static final long serialVersionUID = 1041937899860394478L;

	private static final CLogger log = CLogger.getCLogger(WAttachment.class);

	/**	Window No				*/
	private int	m_WindowNo;

	/** Attachment				*/
	private MAttachment m_attachment = null;

	/** Change					*/
	private boolean m_change = false;

	private Iframe preview = new Iframe();

	protected AMedia media;
	private int mediaVersion = 0;

	private Textbox text = new Textbox();

	private Label sizeLabel = new Label();

	private Listbox cbContent = new Listbox();

	private Button bDelete = ButtonFactory.createNamedButton(ConfirmPanel.A_DELETE, false, true);
	private Button bSave = new Button();
	private Button bSaveAllAsZip = new Button();
	private Button bDeleteAll = new Button();
	private Button bLoad = new Button();
	private Button bCancel = ButtonFactory.createNamedButton(ConfirmPanel.A_CANCEL, false, true);
	private boolean bCancelClicked = false;
	private Button bOk = ButtonFactory.createNamedButton(ConfirmPanel.A_OK, false, true);
	private Button bPreview = new Button();
	private Button bEmail = new Button();

	private Panel previewPanel = new Panel();

	private Borderlayout mainPanel = new Borderlayout();

	private ToolBar toolBar = new ToolBar();

	private Hlayout confirmPanel = new Hlayout();

	private int displayIndex;

	private String orientation;

	private int maxPreviewSize;

	private Component customPreviewComponent;

	private static List<String> autoPreviewList;
	
	/* SysConfig USE_ESC_FOR_TAB_CLOSING */
	private boolean isUseEscForTabClosing = MSysConfig.getBooleanValue(MSysConfig.USE_ESC_FOR_TAB_CLOSING, false, Env.getAD_Client_ID(Env.getCtx()));

	static {
		autoPreviewList = new ArrayList<String>();
		autoPreviewList.add("image/jpeg");
		autoPreviewList.add("image/png");
		autoPreviewList.add("image/gif");
		autoPreviewList.add("text/plain");
		autoPreviewList.add("application/pdf");
		autoPreviewList.add("text/xml");
		autoPreviewList.add("application/json");
		// autoPreviewList.add("text/html"); IDEMPIERE-3980
	}

	/**
	 *	Constructor.
	 *	loads Attachment, if ID &lt;&gt; 0
	 *  @param WindowNo window no
	 *  @param AD_Attachment_ID attachment
	 *  @param AD_Table_ID table
	 *  @param Record_ID record key
	 *  @param trxName transaction
	 */
	@Deprecated
	public WAttachment(	int WindowNo, int AD_Attachment_ID,
						int AD_Table_ID, int Record_ID, String trxName)
	{
		this(WindowNo, AD_Attachment_ID, AD_Table_ID, Record_ID, null, trxName, (EventListener<Event>)null);
	}

	/**
	 *	Constructor.
	 *	loads Attachment, if ID &lt;&gt; 0
	 *  @param WindowNo window no
	 *  @param AD_Attachment_ID attachment
	 *  @param AD_Table_ID table
	 *  @param Record_ID record key
	 *  @param trxName transaction
	 *  @param eventListener
	 */
	@Deprecated
	public WAttachment(	int WindowNo, int AD_Attachment_ID,
			int AD_Table_ID, int Record_ID, String trxName, EventListener<Event> eventListener)
	{
		this(WindowNo, AD_Attachment_ID, AD_Table_ID, Record_ID, null, trxName, eventListener);
	}

	/**
	 *	Constructor.
	 *	loads Attachment, if ID &lt;&gt; 0
	 *  @param WindowNo window no
	 *  @param AD_Attachment_ID attachment
	 *  @param AD_Table_ID table
	 *  @param Record_ID record key
	 *  @param Record_UU record UUID
	 *  @param trxName transaction
	 *  @param eventListener listener for ON_WINDOW_CLOSE event
	 */
	public WAttachment(	int WindowNo, int AD_Attachment_ID,
						int AD_Table_ID, int Record_ID, String Record_UU, String trxName, EventListener<Event> eventListener)
	{
		super();
		maxPreviewSize = MSysConfig.getIntValue(MSysConfig.ZK_MAX_ATTACHMENT_PREVIEW_SIZE, 1048576, Env.getAD_Client_ID(Env.getCtx()));

		if (log.isLoggable(Level.CONFIG)) log.config("ID=" + AD_Attachment_ID + ", Table=" + AD_Table_ID + ", Record=" + Record_ID + ", RecordUU=" + Record_UU);

		m_WindowNo = WindowNo;
		this.addEventListener(DialogEvents.ON_WINDOW_CLOSE, this);
		if (eventListener != null) 
		{
			this.addEventListener(DialogEvents.ON_WINDOW_CLOSE, eventListener);
		}
		
		try
		{
			staticInit();
		}
		catch (Exception ex)
		{
			log.log(Level.SEVERE, "", ex);
		}

		//	Create Model

		if (AD_Attachment_ID > 0)
			m_attachment = new MAttachment (Env.getCtx(), AD_Attachment_ID, trxName);
		else
			m_attachment = new MAttachment (Env.getCtx(), AD_Table_ID, Record_ID, Record_UU, trxName);

		loadAttachments();

		try
		{
			setAttribute(Window.MODE_KEY, Window.MODE_HIGHLIGHTED);
			AEnv.showWindow(this);
			preview.setVisible(false);
			autoPreview(0, true);
		}
		catch (Exception e)
		{
		}

		if (m_attachment.isReadOnly(false)) {
			toolBar.removeChild(bLoad);
			toolBar.removeChild(bDelete);
			confirmPanel.removeChild(bDeleteAll);
			text.setReadonly(true);
		}else {
			// If getUuid is called before the component is attached to page, it's considered a temporary value
			// when component attach to page uuid is re-generate and use as id of DOM element on client
			this.setWidgetOverride("_id_uploadButtonId", "'" + bLoad.getUuid() + "'");
			// set to whole attachment dialog become drop area
			this.setWidgetOverride("_id_isFileDragDropArea", "true");
		}
	} // WAttachment

	/**
	 *	layout window
	 *  <pre>
	 *  - northPanel
	 *      - toolBar
	 *      - title
	 *  - centerPane [split]
	 * 		- previewPanel (left)
	 *  	- text (right)
	 *  - confirmPanel
	 *  </pre>
	 *  @throws Exception
	 */
	protected void staticInit() throws Exception
	{
		this.setAttribute(AdempiereWebUI.WIDGET_INSTANCE_NAME, "attachment");
		this.setMaximizable(true);
		if (!ThemeManager.isUseCSSForWindowSize())
		{
			ZKUpdateUtil.setWindowWidthX(this, 700);
			ZKUpdateUtil.setHeight(this, "85%");
		}
		else
		{
			addCallback(AFTER_PAGE_ATTACHED, t -> {
				ZKUpdateUtil.setCSSHeight(this);
				ZKUpdateUtil.setCSSWidth(this);
			});
		}
		this.setTitle(Msg.getMsg(Env.getCtx(), "Attachment"));
		this.setClosable(true);
		this.setSizable(true);
		this.setBorder("normal");
		this.setSclass("popup-dialog attachment-dialog");
		this.setShadow(true);
		this.appendChild(mainPanel);
		ZKUpdateUtil.setHeight(mainPanel, "100%");
		ZKUpdateUtil.setWidth(mainPanel, "100%");

		North northPanel = new North();
		northPanel.setCollapsible(false);
		northPanel.setSplittable(false);

		cbContent.setMold("select");
		cbContent.setRows(0);
		cbContent.addEventListener(Events.ON_SELECT, this);

		toolBar.setAlign("center");
		toolBar.setOverflowPopup(true);
		toolBar.appendChild(bLoad);
		toolBar.appendChild(bDelete);
		toolBar.appendChild(bSave);
		toolBar.appendChild(bSaveAllAsZip);
		toolBar.appendChild(bEmail);
		toolBar.appendChild(cbContent);
		toolBar.appendChild(sizeLabel);

		mainPanel.appendChild(northPanel);
		
		Vlayout div = new Vlayout();
		div.appendChild(toolBar);
		text.setRows(3);
		ZKUpdateUtil.setHflex(text, "1");
		
		div.appendChild(text);
		northPanel.appendChild(div);

		bSave.setEnabled(false);
		bSave.setSclass("img-btn");
		if (ThemeManager.isUseFontIconForImage())
			bSave.setIconSclass(Icon.getIconSclass(Icon.EXPORT));
		else
			bSave.setImage(ThemeManager.getThemeResource("images/Export24.png"));
		bSave.setTooltiptext(Msg.getMsg(Env.getCtx(), "AttachmentSave"));
		bSave.addEventListener(Events.ON_CLICK, this);

		bSaveAllAsZip.setEnabled(false);
		bSaveAllAsZip.setSclass("img-btn");
		if (ThemeManager.isUseFontIconForImage())
			bSaveAllAsZip.setIconSclass(Icon.getIconSclass(Icon.FILE_ZIP));
		else
			bSaveAllAsZip.setImage(ThemeManager.getThemeResource("images/SaveAsZip24.png"));
		bSaveAllAsZip.setTooltiptext(Msg.getMsg(Env.getCtx(), "ExportZIP"));
		bSaveAllAsZip.addEventListener(Events.ON_CLICK, this);

		if (ThemeManager.isUseFontIconForImage())
			bLoad.setIconSclass(Icon.getIconSclass(Icon.IMPORT));
		else
			bLoad.setImage(ThemeManager.getThemeResource("images/Import24.png"));
		bLoad.setSclass("img-btn");
		bLoad.setId("bLoad");
		bLoad.setTooltiptext(Msg.getMsg(Env.getCtx(), "Load"));
		bLoad.setUpload("multiple=true," + AdempiereWebUI.getUploadSetting());
		bLoad.addEventListener(Events.ON_UPLOAD, this);

		bDelete.addEventListener(Events.ON_CLICK, this);

		bEmail.setEnabled(false);
		if (ThemeManager.isUseFontIconForImage())
			bEmail.setIconSclass(Icon.getIconSclass(Icon.SEND_MAIL));
		else
			bEmail.setImage(ThemeManager.getThemeResource("images/SendMail24.png"));
		bLoad.setSclass("img-btn");
		bEmail.setTooltiptext(Msg.getMsg(Env.getCtx(), "EMail"));
		bEmail.addEventListener(Events.ON_CLICK, this);

		previewPanel.appendChild(preview);
		ZKUpdateUtil.setHeight(preview, "99%");
		ZKUpdateUtil.setWidth(preview, "99%");
		
		Center centerPane = new Center();
		centerPane.setSclass("dialog-content");
		mainPanel.appendChild(centerPane);
		centerPane.appendChild(previewPanel);
		ZKUpdateUtil.setVflex(previewPanel, "1");
		ZKUpdateUtil.setHflex(previewPanel, "1");

		South southPane = new South();
		southPane.setSclass("dialog-footer");
		mainPanel.appendChild(southPane);
		southPane.appendChild(confirmPanel);
		ZKUpdateUtil.setVflex(southPane, "min");

		bCancel.addEventListener(Events.ON_CLICK, this);
		bOk.addEventListener(Events.ON_CLICK, this);

		if (ThemeManager.isUseFontIconForImage())
			bDeleteAll.setIconSclass(Icon.getIconSclass(Icon.DELETE));
		else
			bDeleteAll.setImage(ThemeManager.getThemeResource("images/Delete24.png"));
		bDeleteAll.setSclass("img-btn");
		bDeleteAll.addEventListener(Events.ON_CLICK, this);
		bDeleteAll.setTooltiptext(Util.cleanAmp(Msg.getMsg(Env.getCtx(), "DeleteAll")));

		if (ThemeManager.isUseFontIconForImage())
			bPreview.setIconSclass(Icon.getIconSclass(Icon.FIND));
		else
			bPreview.setImage(ThemeManager.getThemeResource("images/Find24.png"));
		bPreview.setSclass("img-btn");
		bPreview.addEventListener(Events.ON_CLICK, this);
		bPreview.setTooltiptext(Msg.getMsg(Env.getCtx(), "Preview"));

		confirmPanel.appendChild(bDeleteAll);
		confirmPanel.appendChild(bPreview);
		ZKUpdateUtil.setHflex(confirmPanel, "1");
		Hbox hbox = new Hbox();
		hbox.setPack("end");
		ZKUpdateUtil.setHflex(hbox, "1");
		confirmPanel.appendChild(hbox);
		hbox.appendChild(bOk);
		hbox.appendChild(bCancel);
		

		text.setTooltiptext(Msg.getElement(Env.getCtx(), "TextMsg"));
		
		if (ClientInfo.isMobile())
		{
			orientation = ClientInfo.get().orientation;
			ClientInfo.onClientInfo(this, this::onClientInfo);
		}
		addEventListener(Events.ON_CANCEL, e -> onCancel());
	}
	
	/**
	 * Handle onClientInfo event
	 */
	protected void onClientInfo()
	{		
		if (getPage() != null)
		{
			String newOrienation = ClientInfo.get().orientation;
			if (!newOrienation.equals(orientation))
			{
				orientation = newOrienation;
				ZKUpdateUtil.setCSSHeight(this);
				ZKUpdateUtil.setCSSWidth(this);
				invalidate();
			}
		}
	}

	/**
	 * Dispose
	 */
	public void dispose ()
	{
		preview = null;
		if (m_attachment != null) {
            m_attachment.close();
            m_attachment = null;
        }
        this.detach();
    } // dispose

	/**
	 * Load Attachment items
	 */
	private void loadAttachments()
	{
		if (log.isLoggable(Level.CONFIG))
			log.config("");

		//	Set Text/Description

		String sText = m_attachment.getTextMsg();

		if (sText == null)
			text .setText("");
		else
			text.setText(sText);

		//	Set Combo

		int size = m_attachment.getEntryCount();

		for (int i = 0; i < size; i++)
			cbContent.appendItem(m_attachment.getEntryName(i), m_attachment.getEntryName(i));

		if (size > 0)
		{
			cbContent.setSelectedIndex(0);
		}

	} // loadAttachment

	/**
	 * auto preview attachment item
	 * @param index
	 * @param immediate
	 * @return true if preview is available for attachment item
	 */
	private boolean autoPreview(int index, boolean immediate)
	{
		MAttachmentEntry entry = m_attachment.getEntry(index);
		if (entry != null)
		{
			String mimeType = entry.getContentType();
			long entrySize = entry.getSize();
			String unit = " KB";
			BigDecimal size = new BigDecimal(entrySize);
			size = size.divide(new BigDecimal("1024"));
			if (size.compareTo(new BigDecimal("1024")) >= 0)
			{
				size = size.divide(new BigDecimal("1024"));
				unit = " MB";
			}
			size = size.setScale(2, RoundingMode.HALF_EVEN);
			sizeLabel.setText(size.toPlainString() + unit);

			bSave.setEnabled(true);
			bSaveAllAsZip.setEnabled(true);
			bDelete.setEnabled(true);
			bEmail.setEnabled(true);

			if (autoPreviewList.contains(mimeType))
			{
				if (entrySize <= maxPreviewSize) {
					displayData(index, immediate);
				} else {
					clearPreview();
					String msg = WTextEditorDialog.sanitize(Msg.getMsg(Env.getCtx(), "FileTooBigForPreview"));
					Media media = new AMedia(null, null, "text/html", msg.getBytes());
					preview.setContent(media);
					preview.setVisible(true);
					bPreview.setEnabled(true);
					return false;
				}
				return true;
			}
			else
			{
				clearPreview();
				IMediaView view = Extensions.getMediaView(mimeType, getExtension(entry.getName()), ClientInfo.isMobile());
				if (view != null) 
				{
					if (entrySize <= maxPreviewSize) {
						media = new AMedia(entry.getName(), null, mimeType, RepeatableInputStream.getInstance(entry.getInputStream()));
						try {
							customPreviewComponent = view.renderMediaView(previewPanel, media, true);
						} catch (Exception e) {
							log.warning("Error previewing file in attachment entry " + entry.getName() + " -> " + e.getLocalizedMessage());
							e.printStackTrace();
							clearPreview();
							String msg = WTextEditorDialog.sanitize(Msg.getMsg(Env.getCtx(), "ErrorPreviewingFile"));
							Media mediaErr = new AMedia(null, null, "text/html", msg.getBytes());
							preview.setContent(mediaErr);
							preview.setVisible(true);
							bPreview.setEnabled(true);
							return false;
						}
						return true;
					} else {
						return false;
					}
				}
				
				return false;
			}
		}
		else
		{
			bSave.setEnabled(false);
			bSaveAllAsZip.setEnabled(false);
			bDelete.setEnabled(false);
			sizeLabel.setText("");
			bEmail.setEnabled(false);
			return false;
		}
	}

	/**
	 * Get file extension
	 * @param name
	 * @return file extension or empty string
	 */
	private String getExtension(String name) {
		int index = name.lastIndexOf(".");
		if (index > 0) {
			return name.substring(index+1);
		}
		return "";
	}

	/**
	 *  Display attachment item
	 * 	@param index index
	 */

	private void displayData (int index, boolean immediate)
	{
		//	Reset UI
		preview.setSrc(null);

		displayIndex = index;

		if (immediate)
			displaySelected();
		else
			Clients.response(new AuEcho(this, "displaySelected", null));
		bPreview.setEnabled(false);
	}   //  displayData

	/**
	 * Clear preview content ({@link #preview} and {@link #customPreviewComponent})
	 */
	private void clearPreview()
	{
		preview.setSrc(null);
		preview.setVisible(false);
		if (customPreviewComponent != null)
		{
			customPreviewComponent.detach();
			customPreviewComponent = null;
		}
	}

	/**
	 * Use to refresh preview frame, don't call directly.
	 */
	public void displaySelected() {
		MAttachmentEntry entry = m_attachment.getEntry(displayIndex);
		if (log.isLoggable(Level.CONFIG)) log.config("Index=" + displayIndex + " - " + entry);
		if (entry != null && entry.getSize() > 0 && autoPreviewList.contains(entry.getContentType()))
		{
			if (log.isLoggable(Level.CONFIG)) log.config(entry.toStringX());

			try
			{
				String contentType = entry.getContentType();
				media = new AMedia(entry.getName(), null, contentType, RepeatableInputStream.getInstance(entry.getInputStream()));
				if (   MSysConfig.getBooleanValue(MSysConfig.ZK_USE_PDF_JS_VIEWER, false, Env.getAD_Client_ID(Env.getCtx())) 
					&& Medias.PDF_MIME_TYPE.equals(contentType)) {
					mediaVersion++;
					String url = Utils.getDynamicMediaURI(this, mediaVersion, media.getName(), media.getFormat());	
					String pdfJsUrl = AEnv.toPdfJsUrl(url);
					preview.setSrc(pdfJsUrl);
				} else {
					preview.setContent(media);
				}
				preview.setVisible(true);
				preview.invalidate();
			}
			catch (Exception e)
			{
				log.log(Level.SEVERE, "attachment", e);
			}
		}
	}

	/**
	 * 	Get file Name by index
	 *	@param index index
	 *	@return file name or null
	 */
	private String getFileName (int index)
	{
		String fileName = null;

		if (index>=0 && cbContent.getItemCount() > index)
		{
			ListItem listitem = cbContent.getItemAtIndex(index);
			fileName = (String)listitem.getValue();
		}

		return fileName;
	}	//	getFileName

	/**
	 *	handle event
	 *  @param e event
	 */
	@Override
	public void onEvent(Event e)
	{
		//	Save and Close
		if (e instanceof UploadEvent) {
			preview.setVisible(false);
			UploadEvent ue = (UploadEvent) e;
			for (Media media : ue.getMedias()) {
				processUploadMedia(media);
			}
			clearPreview();
			autoPreview (cbContent.getSelectedIndex(), false);
		} else if (e.getTarget() == bOk || (!bCancelClicked && DialogEvents.ON_WINDOW_CLOSE.equals(e.getName()))) {
			if (m_attachment != null) {
				String newText = text.getText();
				if (newText == null)
					newText = "";
				String oldText = m_attachment.getTextMsg();
				if (oldText == null)
					oldText = "";

				if (!m_change)
					m_change = !newText.equals(oldText);

				if (newText.length() > 0 || m_attachment.getEntryCount() > 0) {
					if (m_change) {
						saveAttachment();
					}
				} else {
					m_attachment.delete(true);
					m_attachment = null;
				}

				dispose();
			}
		} else if (e.getTarget() == bCancel) {
			bCancelClicked = true;
			onCancel();
		} else if (e.getTarget() == bDeleteAll) {
			//	Delete Attachment
			deleteAttachment();
		} else if (e.getTarget() == bDelete) {
			//	Delete individual entry and Return
			deleteAttachmentEntry();
		} else if (e.getTarget() == cbContent) {
			//	Show Data
			clearPreview();
			autoPreview (cbContent.getSelectedIndex(), false);
		} else if (e.getTarget() == bSave) {
			//	Open Attachment
			exportAttachmentToFile();
		} else if (e.getTarget() == bPreview) {
			displayData(cbContent.getSelectedIndex(), true);
		} else if (e.getTarget() == bSaveAllAsZip) {
			exportAllAsZip();
		} else if(e.getTarget()==bEmail){
			sendMail();
		}

	}	//	onEvent

	/**
	 * Save the attachment to database
	 */
	private void saveAttachment() {
		m_attachment.setBinaryData(new byte[0]); // ATTENTION! HEAVY HACK HERE... Else it will not save :(
		m_attachment.setTextMsg(text.getText());
		m_attachment.saveEx();
		m_change = false;
	}

	/**
	 * Handle onCancel event
	 */
	private void onCancel() {		
		// do not allow to close tab for Events.ON_CTRL_KEY event
		if(isUseEscForTabClosing)
			SessionManager.getAppDesktop().setCloseTabWithShortcut(false);
		//	Cancel
		dispose();
	}

	/**
	 * Process uploaded media
	 * @param media
	 */
	private void processUploadMedia(Media media) {
		if (media != null)
		{
			;
		}
		else
		{
			preview.setVisible(true);
			preview.invalidate();
			return;
		}

		String fileName = media.getName();
		if (log.isLoggable(Level.CONFIG)) log.config(fileName);
		int cnt = m_attachment.getEntryCount();

		//update
		for (int i = 0; i < cnt; i++)
		{
			if (m_attachment.getEntryName(i).equals(fileName))
			{
                if (media.inMemory()) {
                    byte[] data = getMediaData(media);
                    if (data.length == 0)
                    {
                        preview.setVisible(true);
                        preview.invalidate();
                        return;
                    }
                    m_attachment.updateEntry(i, data);
                }
                else
                {
                    File file = toTempFile(media, fileName);
                    if (file.length() == 0)
                    {
                        preview.setVisible(true);
                        preview.invalidate();
                        return;
                    }
                    m_attachment.updateEntry(i, file);
                }
				cbContent.setSelectedIndex(i);
				m_change = true;
				return;
			}
		}

		//new
        boolean added = false;
        if (media.inMemory())
        {
            byte[] data = getMediaData(media);
            if (data.length == 0)
            {
                preview.setVisible(true);
                preview.invalidate();
                return;
            }
            added = m_attachment.addEntry(fileName, data);
        }
        else
        {
            File file = toTempFile(media, fileName);
            if (file.length() == 0)
            {
                preview.setVisible(true);
                preview.invalidate();
                return;
            }
            added = m_attachment.addEntry(fileName, file);
        }
		if (added)
		{
			cbContent.appendItem(media.getName(), media.getName());
			cbContent.setSelectedIndex(cbContent.getItemCount()-1);
			m_change = true;
		}
	}

    private File toTempFile(Media media, String fileName) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("attachment_");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try (InputStream inputStream = media.getStreamData()) {
            Path tempFile = tempDir.resolve(fileName);
            Files.copy(inputStream, tempFile);
            return tempFile.toFile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
	 * Get byte[] data from media
	 * @param media
	 * @return byte[] data
	 */
	private byte[] getMediaData(Media media)  {
		byte[] bytes = null;
		
		try {
            bytes = media.isBinary() ? media.getByteData() : media.getStringData().getBytes(getCharset(media.getContentType()));
		} catch (IOException e) {
			log.log(Level.SEVERE, e.getLocalizedMessage(), e);
			throw new IllegalStateException(e.getLocalizedMessage());
		}

		return bytes;
	}

	/**
	 * Delete entire Attachment
	 */
	private void deleteAttachment()
	{
		if (log.isLoggable(Level.INFO))
			log.info("");

		Dialog.ask(m_WindowNo, "AttachmentDelete?", new Callback<Boolean>() {
			
			@Override
			public void onCallback(Boolean result) 
			{
				if (result)
				{
					if (m_attachment != null) {
						m_attachment.delete(true);
						m_attachment = null;
					}
					dispose();
				}					
			}
		});			
	}	//	deleteAttachment

	/**
	 * Delete current Attachment Entry
	 */
	private void deleteAttachmentEntry()
	{
		if (log.isLoggable(Level.INFO))
			log.info("");

		final int index = cbContent.getSelectedIndex();
		String fileName = getFileName(index);

		if (fileName == null)
			return;

		Dialog.ask(m_WindowNo, "AttachmentDeleteEntry?", new Callback<Boolean>() {

			@Override
			public void onCallback(Boolean result) 
			{
				if (result)
				{
					if (m_attachment.deleteEntry(index)) {
						// must save the attachment immediately, on external storage providers the file doesn't exist at this point
						saveAttachment();
						cbContent.removeItemAt(index);
						clearPreview();
						autoPreview (cbContent.getSelectedIndex(), true);
					}

				}				
			}
		});		
	}	//	deleteAttachment

	/**
	 * Export current Attachment entry to File
	 */
	private void exportAttachmentToFile()
	{
		int index = cbContent.getSelectedIndex();
		if (log.isLoggable(Level.INFO))
			log.info("index=" + index);

		if (m_attachment.getEntryCount() < index)
			return;

		MAttachmentEntry entry = m_attachment.getEntry(index);
		if (entry != null && entry.getSize() > 0)
		{
			try
			{
				media = new AMedia(entry.getName(), null, entry.getContentType(), RepeatableInputStream.getInstance(entry.getInputStream()));
				Filedownload.save(media);
			}
			catch (Exception e)
			{
				log.log(Level.SEVERE, "attachment", e);
			}
		}
	}	//	saveAttachmentToFile
	
	/**
	 * Get charset from content type header. Fallback to UTF-8
	 * @param contentType
	 * @return charset
	 */
	static private String getCharset(String contentType) {
		if (contentType != null) {
			int j = contentType.indexOf("charset=");
			if (j >= 0) {
				String cs = contentType.substring(j + 8).trim();
				if (cs.length() > 0) return cs;
			}
		}
		return "UTF-8";
	}	

	/**
	 * Export all attachment items as zip file
	 */
	private void exportAllAsZip() {
		File zipFile = m_attachment.saveAsZip();
		
		if (zipFile != null) {
			String name = MTable.get(Env.getCtx(), m_attachment.getAD_Table_ID()).getTableName() + "_" + m_attachment.getRecord_ID();
			media = null;
			try {
				media = new AMedia(name, null, "application/zip", zipFile, true);
			} catch (Exception e) {
				throw new AdempiereException("Error when converting zip file to media : " + e);
			}			
			Filedownload.save(media);
		}
	}
	
	/**
	 * Email current attachment entry
	 */
	private void sendMail()
	{
		int index = cbContent.getSelectedIndex();

		MUser from = MUser.get(Env.getCtx(), Env.getAD_User_ID(Env.getCtx()));
		String fileName = System.getProperty("java.io.tmpdir") +
		System.getProperty("file.separator") + m_attachment.getEntryName(index);
		File attachment = new File(fileName);
		m_attachment.getEntryFile(index, attachment);

		WEMailDialog dialog = new WEMailDialog (Msg.getMsg(Env.getCtx(), "SendMail"),
			from, "", "", "", new FileDataSource(attachment),
			m_WindowNo, m_attachment.getAD_Table_ID(), m_attachment.getRecord_ID(), m_attachment.getRecord_UU(), null);

		AEnv.showWindow(dialog);
	}

	//-- ComponentCtrl --//
	public Object getExtraCtrl() {
		return new ExtraCtrl();
	}
	/** A utility class to implement {@link #getExtraCtrl}.
	 * It is used only by component developers.
	 */
	protected class ExtraCtrl extends XulElement.ExtraCtrl
	implements DynamicMedia {
		//-- DynamicMedia --//
		public Media getMedia(String pathInfo) {
			return media;
		}
	}

}
