package com.hiddenramblings.tagmo.fragment

import android.annotation.SuppressLint
import android.app.Dialog
import android.app.SearchManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothAdapter.LeScanCallback
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.*
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.*
import androidx.cardview.widget.CardView
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.request.target.CustomTarget
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback
import com.google.android.material.snackbar.Snackbar
import com.hiddenramblings.tagmo.*
import com.hiddenramblings.tagmo.adapter.GattSlotAdapter
import com.hiddenramblings.tagmo.adapter.WriteTagAdapter
import com.hiddenramblings.tagmo.amiibo.Amiibo
import com.hiddenramblings.tagmo.amiibo.AmiiboFile
import com.hiddenramblings.tagmo.amiibo.AmiiboManager
import com.hiddenramblings.tagmo.amiibo.AmiiboManager.getAmiiboManager
import com.hiddenramblings.tagmo.amiibo.AmiiboManager.hasSpoofData
import com.hiddenramblings.tagmo.amiibo.BluupTag
import com.hiddenramblings.tagmo.amiibo.KeyManager
import com.hiddenramblings.tagmo.amiibo.tagdata.AmiiboData
import com.hiddenramblings.tagmo.bluetooth.BluetoothHandler
import com.hiddenramblings.tagmo.bluetooth.BluetoothHandler.BluetoothListener
import com.hiddenramblings.tagmo.bluetooth.GattService
import com.hiddenramblings.tagmo.bluetooth.Nordic
import com.hiddenramblings.tagmo.eightbit.io.Debug
import com.hiddenramblings.tagmo.eightbit.material.IconifiedSnackbar
import com.hiddenramblings.tagmo.eightbit.os.Version
import com.hiddenramblings.tagmo.eightbit.request.ImageTarget
import com.hiddenramblings.tagmo.nfctech.TagArray
import com.hiddenramblings.tagmo.widget.Toasty
import com.shawnlin.numberpicker.NumberPicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.text.ParseException

@SuppressLint("NewApi")
open class GattSlotFragment : Fragment(), GattSlotAdapter.OnAmiiboClickListener, BluetoothListener {
    private val prefs: Preferences by lazy { Preferences(TagMo.appContext) }
    private val keyManager: KeyManager by lazy { (requireActivity() as BrowserActivity).keyManager }

    private var bluetoothHandler: BluetoothHandler? = null
    private var isFragmentVisible = false
    private var amiiboTile: CardView? = null
    private var amiiboCard: CardView? = null
    private var toolbar: Toolbar? = null
    var bluupContent: RecyclerView? = null
        private set
    var gattAdapter: GattSlotAdapter? = null
    private var gattStats: TextView? = null
    private lateinit var gattSlotCount: NumberPicker
    private var screenOptions: LinearLayout? = null
    private var writeSlots: AppCompatButton? = null
    private var writeRandom: SwitchCompat? = null
    private var eraseSlots: AppCompatButton? = null
    private var slotOptionsMenu: LinearLayout? = null
    private var createBlank: AppCompatButton? = null
    private var switchMenuOptions: AppCompatToggleButton? = null
    private var writeSlotsLayout: LinearLayout? = null
    private var writeTagAdapter: WriteTagAdapter? = null
    private var statusBar: Snackbar? = null
    private var processDialog: Dialog? = null
    private lateinit var settings: BrowserSettings
    var bottomSheet: BottomSheetBehavior<View>? = null
        private set
    private var mBluetoothAdapter: BluetoothAdapter? = null
    private var scanCallbackNordicLP: ScanCallback? = null
    private var scanCallbackLegacyLP: ScanCallback? = null
    private var scanCallbackNordic: LeScanCallback? = null
    private var scanCallbackLegacy: LeScanCallback? = null
    private var serviceGatt: GattService? = null
    private var deviceProfile: String? = null
    private var deviceAddress: String? = null
    private var maxSlotCount = 0
    private var currentCount = 0
    private var deviceDialog: AlertDialog? = null

    private var deviceType = Nordic.DEVICE.GATT
    private val chunkTimeout = 25L

    private enum class STATE {
        NONE, SCANNING, CONNECT, MISSING, TIMEOUT
    }

    private var noticeState = STATE.NONE

    private enum class SHEET {
        LOCKED, AMIIBO, MENU, WRITE
    }

    private var browserActivity: BrowserActivity? = null
    private val fragmentHandler = Handler(Looper.getMainLooper())

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (activity is BrowserActivity) browserActivity = activity as BrowserActivity
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_gatt_slot, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) return

        amiiboTile = view.findViewById(R.id.active_tile_layout)
        amiiboCard = view.findViewById<CardView>(R.id.active_card_layout).apply {
            findViewById<View>(R.id.txtError)?.isGone = true
            findViewById<View>(R.id.txtPath)?.isGone = true
        }

        toolbar = view.findViewById(R.id.toolbar)

        browserActivity?.let { activity ->
            settings = activity.settings ?: BrowserSettings().initialize()

            bluupContent = view.findViewById<RecyclerView>(R.id.bluup_content).apply {
                layoutManager = if (settings.amiiboView == BrowserSettings.VIEW.IMAGE.value)
                    GridLayoutManager(activity, activity.columnCount)
                else
                    LinearLayoutManager(activity)
            }

            gattStats = view.findViewById(R.id.bluup_stats)
            switchMenuOptions = view.findViewById(R.id.switch_menu_btn)
            slotOptionsMenu = view.findViewById(R.id.slot_options_menu)

            createBlank = view.findViewById<AppCompatButton>(R.id.create_blank).apply {
                setOnClickListener { serviceGatt?.createBlankTag() }
            }

            screenOptions = view.findViewById(R.id.screen_options)

            val searchView = view.findViewById<SearchView>(R.id.amiibo_search)
            if (BuildConfig.WEAR_OS) {
                searchView.isGone = true
            } else {
                with(activity.getSystemService(Context.SEARCH_SERVICE) as SearchManager) {
                    searchView.setSearchableInfo(getSearchableInfo(activity.componentName))
                }
                searchView.isSubmitButtonEnabled = false
                searchView.setIconifiedByDefault(false)
                val searchBar = searchView.findViewById<LinearLayout>(R.id.search_bar)
                searchBar.layoutParams.height = resources
                    .getDimension(R.dimen.button_height_min).toInt()
                searchBar.gravity = Gravity.CENTER_VERTICAL
                searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                    override fun onQueryTextSubmit(query: String): Boolean {
                        settings.query = query
                        settings.notifyChanges()
                        return false
                    }

                    override fun onQueryTextChange(query: String): Boolean {
                        settings.query = query
                        settings.notifyChanges()
                        return true
                    }
                })
            }

            view.findViewById<AppCompatButton>(R.id.write_slot_file).apply {
                setOnClickListener {
                    settings.addChangeListener(writeTagAdapter)
                    onBottomSheetChanged(SHEET.WRITE)
                    searchView.setQuery(settings.query, true)
                    searchView.clearFocus()
                    writeTagAdapter?.setListener(object : WriteTagAdapter.OnAmiiboClickListener {
                        override fun onAmiiboClicked(amiiboFile: AmiiboFile?) {
                            onBottomSheetChanged(SHEET.AMIIBO)
                            showProcessingNotice(true)
                            uploadAmiiboFile(amiiboFile)
                            settings.removeChangeListener(writeTagAdapter)
                        }

                        override fun onAmiiboImageClicked(amiiboFile: AmiiboFile?) {
                            handleImageClicked(amiiboFile)
                        }

                        override fun onAmiiboListClicked(amiiboList: ArrayList<AmiiboFile?>?) {}
                        override fun onAmiiboDataClicked(amiiboFile: AmiiboFile?, count: Int) {}
                    })
                    bottomSheet?.setState(BottomSheetBehavior.STATE_EXPANDED)
                }
            }

            writeRandom = view.findViewById(R.id.write_fill_random)

            writeSlots = view.findViewById<AppCompatButton>(R.id.write_slot_count).apply {
                text = getString(R.string.write_slots, 1)
                setOnClickListener {
                    settings.addChangeListener(writeTagAdapter)
                    onBottomSheetChanged(SHEET.WRITE)
                    searchView.setQuery(settings.query, true)
                    searchView.clearFocus()
                    gattSlotCount.value.let { count ->
                        writeTagAdapter?.setListener(object : WriteTagAdapter.OnAmiiboClickListener {
                            override fun onAmiiboClicked(amiiboFile: AmiiboFile?) {}
                            override fun onAmiiboImageClicked(amiiboFile: AmiiboFile?) {}
                            override fun onAmiiboListClicked(amiiboList: ArrayList<AmiiboFile?>?) {
                                if (!amiiboList.isNullOrEmpty()) writeAmiiboFileCollection(amiiboList)
                            }

                            override fun onAmiiboDataClicked(amiiboFile: AmiiboFile?, count: Int) {
                                CoroutineScope(Dispatchers.IO).launch {
                                    amiiboFile?.let {
                                        val amiiboData = it.withRandomSerials(keyManager, count)
                                        withContext(Dispatchers.Main) {
                                            writeAmiiboDataCollection(amiiboData)
                                        }
                                    }
                                }
                            }
                        }, count, writeRandom?.isChecked ?: false)
                    }
                    bottomSheet?.setState(BottomSheetBehavior.STATE_EXPANDED)
                }
            }

            eraseSlots = view.findViewById<AppCompatButton>(R.id.erase_slot_count).apply {
                text = getString(R.string.erase_slots, 0)
                setOnClickListener {
                    AlertDialog.Builder(requireContext())
                        .setMessage(R.string.gatt_erase_confirm)
                        .setPositiveButton(R.string.cancel) { dialog: DialogInterface, _: Int ->
                            dialog.dismiss()
                        }
                        .setNegativeButton(R.string.proceed) { _: DialogInterface?, _: Int ->
                            showProcessingNotice(false)
                            serviceGatt?.clearStorage(currentCount)
                        }
                        .show()
                }
            }

            gattSlotCount = view.findViewById<NumberPicker>(R.id.number_picker_slot).apply {
                if (TagMo.isUserInputEnabled) setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                setOnValueChangedListener { _, _, newVal ->
                    if (maxSlotCount - currentCount > 0)
                        bluupContent?.post {
                            writeSlots?.text = getString(R.string.write_slots, newVal)
                        }
                }
            }

            writeSlotsLayout = view.findViewById(R.id.write_list_slots)

            view.findViewById<RecyclerView>(R.id.amiibo_files_list).apply {
                layoutManager = if (settings.amiiboView == BrowserSettings.VIEW.IMAGE.value)
                    GridLayoutManager(activity, activity.columnCount)
                else
                    LinearLayoutManager(activity)
                writeTagAdapter = WriteTagAdapter(settings).also { adapter = it }
                FastScrollerBuilder(this).build()
            }
        }

        val toggle = view.findViewById<AppCompatImageView>(R.id.toggle)
        bottomSheet = BottomSheetBehavior.from(view.findViewById(R.id.bottom_sheet_slot)).apply {
            state = BottomSheetBehavior.STATE_COLLAPSED
            var slideHeight = 0F
            addBottomSheetCallback(object : BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                        if (writeSlotsLayout?.visibility == View.VISIBLE)
                            onBottomSheetChanged(SHEET.MENU)
                        toggle.setImageResource(R.drawable.ic_expand_less_24dp)
                        bluupContent?.setPadding(0, 0, 0, 0)
                    } else if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                        toggle.setImageResource(R.drawable.ic_expand_more_24dp)
                        bluupContent?.let {
                            val bottomHeight: Int = (view.measuredHeight - peekHeight)
                            it.setPadding(0, 0, 0, if (slideHeight > 0)
                                (bottomHeight * slideHeight).toInt() else 0
                            )
                        }

                    }
                }

                override fun onSlide(view: View, slideOffset: Float) { slideHeight = slideOffset }
            })
        }.also { bottomSheet ->
            setBottomSheetHidden(false)
            toggle.setOnClickListener {
                if (bottomSheet.state == BottomSheetBehavior.STATE_COLLAPSED) {
                    bottomSheet.setState(BottomSheetBehavior.STATE_EXPANDED)
                } else {
                    bottomSheet.setState(BottomSheetBehavior.STATE_COLLAPSED)
                }
            }
        }
        toggle.setImageResource(R.drawable.ic_expand_more_24dp)
        toolbar?.inflateMenu(R.menu.bluup_menu)

        view.findViewById<View>(R.id.switch_devices).setOnClickListener {
            bottomSheet?.state = BottomSheetBehavior.STATE_COLLAPSED
            disconnectService()
            if (isBluetoothEnabled) selectBluetoothDevice()
        }
        switchMenuOptions?.setOnClickListener {
            if (slotOptionsMenu?.isShown == true) {
                onBottomSheetChanged(SHEET.AMIIBO)
            } else {
                onBottomSheetChanged(SHEET.MENU)
            }
            bottomSheet?.setState(BottomSheetBehavior.STATE_EXPANDED)
        }

        view.findViewById<View>(R.id.screen_layered)
            .setOnClickListener { serviceGatt?.setFlaskFace(false) }
        view.findViewById<View>(R.id.screen_stacked)
            .setOnClickListener { serviceGatt?.setFlaskFace(true) }
        gattButtonState
    }

    private fun onBottomSheetChanged(sheet: SHEET) {
        bottomSheet?.state = BottomSheetBehavior.STATE_COLLAPSED
        bottomSheet?.isFitToContents = true
        requireActivity().runOnUiThread {
            when (sheet) {
                SHEET.LOCKED -> {
                    amiiboCard?.isGone = true
                    switchMenuOptions?.isGone = true
                    slotOptionsMenu?.isGone = true
                    writeSlotsLayout?.isGone = true
                }
                SHEET.AMIIBO -> {
                    amiiboCard?.isVisible = true
                    switchMenuOptions?.isVisible = true
                    slotOptionsMenu?.isGone = true
                    writeSlotsLayout?.isGone = true
                }
                SHEET.MENU -> {
                    amiiboCard?.isGone = true
                    switchMenuOptions?.isVisible = true
                    slotOptionsMenu?.isVisible = true
                    writeSlotsLayout?.isGone = true
                }
                SHEET.WRITE -> {
                    bottomSheet?.isFitToContents = false
                    amiiboCard?.isGone = true
                    switchMenuOptions?.isGone = true
                    slotOptionsMenu?.isGone = true
                    writeSlotsLayout?.isVisible = true
                }
            }
        }
    }

    fun setAmiiboInfoText(textView: TextView?, text: CharSequence?) {
        textView?.isVisible = true
        if (!text.isNullOrEmpty()) {
            textView?.text = text
            textView?.isEnabled = true
        } else {
            textView?.setText(R.string.unknown)
            textView?.isEnabled = false
        }
    }

    private val gattButtonState: Unit
        get() {
            bluupContent?.post {
                val openSlots = maxSlotCount - currentCount
                gattSlotCount.value = openSlots
                if (openSlots > 0) {
                    writeSlots?.isEnabled = true
                    writeSlots?.text = getString(R.string.write_slots, openSlots)
                } else {
                    writeSlots?.isEnabled = false
                    writeSlots?.text = getString(R.string.slots_full)
                }
                if (currentCount > 0) {
                    eraseSlots?.isEnabled = true
                    eraseSlots?.text = getString(R.string.erase_slots, currentCount)
                } else {
                    eraseSlots?.isEnabled = false
                    eraseSlots?.text = getString(R.string.slots_empty)
                }
            }
        }

    private fun resetActiveSlot() {
        gattAdapter?.getItem(0).run {
            if (this is BluupTag) {
                serviceGatt?.setActiveAmiibo(name, String(TagArray.longToBytes(id)))
            } else {
                this?.let { serviceGatt?.setActiveAmiibo(it.name, it.bluupTail) }
            }
        }
    }

    private fun getActiveAmiibo(active: Amiibo?, amiiboView: View?) {
        if (null == amiiboView) return
        val txtName = amiiboView.findViewById<TextView>(R.id.txtName)
        val txtTagId = amiiboView.findViewById<TextView>(R.id.txtTagId)
        val txtAmiiboSeries = amiiboView.findViewById<TextView>(R.id.txtAmiiboSeries)
        val txtAmiiboType = amiiboView.findViewById<TextView>(R.id.txtAmiiboType)
        val txtGameSeries = amiiboView.findViewById<TextView>(R.id.txtGameSeries)
        val imageAmiibo = amiiboView.findViewById<AppCompatImageView>(R.id.imageAmiibo)
        val txtUsageLabel = amiiboView.findViewById<TextView>(R.id.txtUsageLabel)
        requireView().post {
            val amiiboHexId: String
            val amiiboName: String?
            var amiiboSeries = ""
            var amiiboType = ""
            var gameSeries = ""
            var amiiboImageUrl: String? = null
            if (amiiboView === amiiboTile) amiiboView.isVisible = true
            if (null == active) {
                txtName.setText(R.string.no_tag_loaded)
                txtTagId?.isInvisible = true
                txtAmiiboSeries.isInvisible = true
                txtAmiiboType.isInvisible = true
                txtGameSeries.isInvisible = true
                if (amiiboView === amiiboCard) txtUsageLabel.isInvisible = true
            } else if (active is BluupTag) {
                txtName.setText(R.string.blank_tag)
                txtTagId?.isInvisible = true
                txtAmiiboSeries.isInvisible = true
                txtAmiiboType.isInvisible = true
                txtGameSeries.isInvisible = true
                if (amiiboView === amiiboCard) txtUsageLabel.isInvisible = true
            } else {
                txtTagId?.isInvisible = false
                txtAmiiboSeries.isInvisible = false
                txtAmiiboType.isInvisible = false
                txtGameSeries.isInvisible = false
                if (amiiboView === amiiboCard) txtUsageLabel.isInvisible = false
                amiiboHexId = Amiibo.idToHex(active.id)
                amiiboName = active.name
                amiiboImageUrl = active.imageUrl
                active.amiiboSeries?.let { amiiboSeries = it.name }
                active.amiiboType?.let { amiiboType = it.name }
                active.gameSeries?.let { gameSeries = it.name }
                setAmiiboInfoText(txtName, amiiboName)
                setAmiiboInfoText(txtTagId, amiiboHexId)
                setAmiiboInfoText(txtAmiiboSeries, amiiboSeries)
                setAmiiboInfoText(txtAmiiboType, amiiboType)
                setAmiiboInfoText(txtGameSeries, gameSeries)
                if (hasSpoofData(amiiboHexId)) txtTagId.isEnabled = false
            }

            imageAmiibo?.let {
                if (amiiboView === amiiboCard && null == amiiboImageUrl) {
                    it.setImageResource(0)
                    it.isInvisible = true
                } else if (amiiboView !== amiiboTile || null != amiiboImageUrl) {
                    if (amiiboView === amiiboCard) {
                        it.setImageResource(0)
                        it.isGone = true
                    }
                    if (!amiiboImageUrl.isNullOrEmpty()) {
                        val imageTarget: CustomTarget<Bitmap?> = if (amiiboView === amiiboCard) {
                            ImageTarget.getTargetHR(it)
                        } else {
                            ImageTarget.getTargetR(it)
                        }
                        GlideApp.with(it).clear(it)
                        GlideApp.with(it).asBitmap().load(amiiboImageUrl).into(imageTarget)
                    }
                    it.setOnClickListener {
                        startActivity(Intent(requireContext(), ImageActivity::class.java)
                            .putExtras(Bundle().apply {
                                putLong(NFCIntent.EXTRA_AMIIBO_ID, active!!.id)
                            })
                        )
                    }
                }
            }
        }
    }

    private fun getAmiiboFromTail(name: List<String>): Amiibo? {
        when {
            name.size < 2 -> return null
            name[0].startsWith("New Tag") || name[1].isEmpty() -> return BluupTag(name)
            else -> {
                var amiiboManager: AmiiboManager?
                try {
                    amiiboManager = getAmiiboManager(requireContext().applicationContext)
                } catch (e: IOException) {
                    Debug.warn(e)
                    amiiboManager = null
                    Toasty(requireActivity()).Short(R.string.amiibo_info_parse_error)
                } catch (e: JSONException) {
                    Debug.warn(e)
                    amiiboManager = null
                    Toasty(requireActivity()).Short(R.string.amiibo_info_parse_error)
                } catch (e: ParseException) {
                    Debug.warn(e)
                    amiiboManager = null
                    Toasty(requireActivity()).Short(R.string.amiibo_info_parse_error)
                }
                var selectedAmiibo: Amiibo? = null
                amiiboManager?.let {
                    val matches : ArrayList<Amiibo> = arrayListOf()
                    for (amiibo in it.amiibos.values) {
                        if (name[1] == amiibo.bluupTail) {
                            if (amiibo.bluupName == name[0]) {
                                selectedAmiibo = amiibo
                                matches.clear()
                                break
                            } else {
                                matches.add(amiibo)
                            }
                        }
                        selectedAmiibo = matches.find { slot ->
                            null == slot.bluupName
                        }?.apply { bluupName = name[0] }
                    }
                    if (null == selectedAmiibo && matches.isNotEmpty())
                        selectedAmiibo = matches[0]
                }
                return selectedAmiibo
            }
        }
    }

    private fun getAmiiboFromSlice(byteData: ByteArray): Amiibo? {
        var amiiboManager: AmiiboManager?
        try {
            amiiboManager = getAmiiboManager(requireContext().applicationContext)
        } catch (e: IOException) {
            Debug.warn(e)
            amiiboManager = null
            Toasty(requireActivity()).Short(R.string.amiibo_info_parse_error)
        } catch (e: JSONException) {
            Debug.warn(e)
            amiiboManager = null
            Toasty(requireActivity()).Short(R.string.amiibo_info_parse_error)
        } catch (e: ParseException) {
            Debug.warn(e)
            amiiboManager = null
            Toasty(requireActivity()).Short(R.string.amiibo_info_parse_error)
        }
        if (Thread.currentThread().isInterrupted) return null
        var selectedAmiibo: Amiibo? = null
        amiiboManager?.let {
            try {
                val hexData = TagArray.bytesToHex(byteData)
                val amiiboId = TagArray.hexToLong(hexData.substring(82, 98))
                if (it.amiibos.containsKey(amiiboId)) {
                    selectedAmiibo = it.amiibos[amiiboId]
                }
            } catch (e: Exception) { Debug.info(e) }
        }
        return selectedAmiibo
    }

    @SuppressLint("InflateParams")
    private fun displayScanResult(
        deviceDialog: AlertDialog, device: BluetoothDevice, detectedType: Nordic.DEVICE
    ) : View {
        val item = this.layoutInflater.inflate(R.layout.device_bluetooth, null)
        item.findViewById<TextView>(R.id.device_name).text = device.name
        item.findViewById<TextView>(R.id.device_type).text = getDeviceTypeName(detectedType)
        item.findViewById<TextView>(R.id.device_address).text =
            requireActivity().getString(R.string.device_address, device.address)

        item.findViewById<View>(R.id.connect_bluup).run {
            setOnClickListener {
                deviceDialog.dismiss()
                deviceProfile = device.name
                deviceAddress = device.address
                deviceType = detectedType
                dismissGattDiscovery()
                showConnectionNotice()
                startGattService()
            }
        }

        item.findViewById<View>(R.id.connect_pixl).run {
            setOnClickListener {
                deviceDialog.dismiss()
                deviceProfile = device.name
                deviceAddress = device.address
                deviceType = detectedType
                dismissGattDiscovery()
                AlertDialog.Builder(requireContext())
                        .setMessage(getString(R.string.pixl_type_warning, getDeviceTypeName(deviceType)))
                        .setPositiveButton(R.string.device_loop) { dialog: DialogInterface, _: Int ->
                            dialog.dismiss()
                            deviceType = Nordic.DEVICE.LOOP
                            showConnectionNotice()
                            startGattService()
                        }
                        .setNegativeButton(R.string.device_link) { dialog: DialogInterface, _: Int ->
                            dialog.dismiss()
                            deviceType = Nordic.DEVICE.LINK
                            showConnectionNotice()
                            startGattService()
                        }
                        .setNeutralButton(R.string.device_pixl) { dialog: DialogInterface, _: Int ->
                            dialog.dismiss()
                            deviceType = Nordic.DEVICE.PIXL
                            showConnectionNotice()
                            startGattService()
                        }
                        .show()
            }
        }

        item.findViewById<View>(R.id.connect_puck).run {
            setOnClickListener {
                deviceDialog.dismiss()
                deviceProfile = device.name
                deviceAddress = device.address
                deviceType = detectedType
                dismissGattDiscovery()
                showConnectionNotice()
                startGattService()
            }
        }
        return item
    }

    private fun getDeviceType(device: BluetoothDevice): Nordic.DEVICE {
        return device.name.let {
             when {
                 it.lowercase().startsWith("flask") -> Nordic.DEVICE.FLASK
                 it.lowercase().startsWith("slide") -> Nordic.DEVICE.SLIDE
                 it.lowercase().startsWith("puck.js") -> Nordic.DEVICE.PUCK
                 it.lowercase().startsWith("amiibolink") -> Nordic.DEVICE.LINK
                 it.lowercase().startsWith("amiloop") -> Nordic.DEVICE.LOOP
                 it.lowercase().startsWith("pixl.js")-> Nordic.DEVICE.PIXL
                else -> Nordic.DEVICE.GATT
            }
        }
    }

    private fun getDeviceTypeName(deviceType:  Nordic.DEVICE) : String {
        return when(deviceType) {
            Nordic.DEVICE.FLASK -> getString(R.string.device_flask)
            Nordic.DEVICE.SLIDE -> getString(R.string.device_slide)
            Nordic.DEVICE.BLUUP -> getString(R.string.device_espruino)
            Nordic.DEVICE.LINK -> getString(R.string.device_link)
            Nordic.DEVICE.LOOP -> getString(R.string.device_loop)
            Nordic.DEVICE.PIXL -> getString(R.string.device_pixl)
            Nordic.DEVICE.PUCK -> getString(R.string.device_puck)
            else -> getString(R.string.unknown)
        }
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun scanBluetoothServices(deviceDialog: AlertDialog) {
        mBluetoothAdapter = mBluetoothAdapter
            ?: bluetoothHandler?.getBluetoothAdapter(requireContext())
        if (null == mBluetoothAdapter) {
            setBottomSheetHidden(true)
            Toasty(requireActivity()).Long(R.string.fail_bluetooth_adapter)
            return
        }
        showScanningNotice()
        deviceProfile = null
        val devices: ArrayList<BluetoothDevice> = arrayListOf()
        if (Version.isLollipop) {
            val scanner = mBluetoothAdapter?.bluetoothLeScanner
            val settings = ScanSettings.Builder().setScanMode(
                ScanSettings.SCAN_MODE_LOW_LATENCY
            ).build()

            val filterNordic = ScanFilter.Builder().setServiceUuid(ParcelUuid(Nordic.NUS)).build()
            scanCallbackNordicLP = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    super.onScanResult(callbackType, result)
                    if (!devices.contains(result.device)) {
                        devices.add(result.device)
                        deviceDialog.findViewById<LinearLayout>(R.id.bluetooth_result)?.addView(
                            displayScanResult(deviceDialog, result.device, getDeviceType(result.device))
                        )
                    }
                }
            }
            scanner?.startScan(listOf(filterNordic), settings, scanCallbackNordicLP)

            val filterLegacy = ScanFilter.Builder().setServiceUuid(ParcelUuid(Nordic.LegacyNUS)).build()
            scanCallbackLegacyLP = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    super.onScanResult(callbackType, result)
                    if (!devices.contains(result.device)) {
                        devices.add(result.device)
                        deviceDialog.findViewById<LinearLayout>(R.id.bluetooth_result)?.addView(
                                displayScanResult(deviceDialog, result.device, getDeviceType(result.device))
                        )
                    }
                }
            }
            scanner?.startScan(listOf(filterLegacy), settings, scanCallbackLegacyLP)
        } else @Suppress("DEPRECATION") {
            scanCallbackNordic =
                LeScanCallback { device: BluetoothDevice, _: Int, _: ByteArray? ->
                    if (!devices.contains(device)) {
                        devices.add(device)
                        deviceDialog.findViewById<LinearLayout>(R.id.bluetooth_result)?.addView(
                            displayScanResult(deviceDialog, device, getDeviceType(device))
                        )
                    }
                }
            mBluetoothAdapter?.startLeScan(arrayOf(Nordic.NUS), scanCallbackNordic)

            scanCallbackLegacy =
                    LeScanCallback { device: BluetoothDevice, _: Int, _: ByteArray? ->
                        if (!devices.contains(device)) {
                            devices.add(device)
                            deviceDialog.findViewById<LinearLayout>(R.id.bluetooth_result)?.addView(
                                    displayScanResult(deviceDialog, device, getDeviceType(device))
                            )
                        }
                    }
            mBluetoothAdapter?.startLeScan(arrayOf(Nordic.LegacyNUS), scanCallbackLegacy)
        }
        fragmentHandler.postDelayed({
            if (null == deviceProfile) {
                dismissGattDiscovery()
                showTimeoutNotice()
            }
        }, 30000)
    }

    @SuppressLint("MissingPermission")
    private fun selectBluetoothDevice() {
        if (mBluetoothAdapter?.isEnabled != true) {
            Toasty(requireActivity()).Long(R.string.fail_bluetooth_adapter)
            return
        }
        if (deviceDialog?.isShowing == true) return
        val view = this.layoutInflater.inflate(R.layout.dialog_devices, null) as LinearLayout
        view.findViewById<AppCompatButton>(R.id.shop_hardware).setOnClickListener {
            startActivity(Intent(
                Intent.ACTION_VIEW, Uri.parse("https://www.nintendo.com/store/hardware/amiibo/")
            ))
        }
        deviceDialog = AlertDialog.Builder(requireActivity()).setView(view).show().apply {
            mBluetoothAdapter?.bondedDevices?.forEach { device ->
                if (null != device.name) {
                    view.findViewById<LinearLayout>(R.id.bluetooth_paired)?.addView(
                        displayScanResult(this, device, getDeviceType(device))
                    )
                }
            }
            scanBluetoothServices(this)
            setOnCancelListener { disconnectService() }
        }
    }

    private fun writeAmiiboDataCollection(bytesList: ArrayList<AmiiboData>) {
        settings.removeChangeListener(writeTagAdapter)
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.gatt_write_confirm)
            .setPositiveButton(R.string.proceed) { dialog: DialogInterface, _: Int ->
                showProcessingNotice(true)
                bytesList.forEachIndexed { i, byte ->
                    fragmentHandler.postDelayed({
                        uploadAmiiboData(byte, i == bytesList.size - 1)
                    }, chunkTimeout * i)
                }
                onBottomSheetChanged(SHEET.MENU)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog: DialogInterface, _: Int ->
                onBottomSheetChanged(SHEET.MENU)
                dialog.dismiss()
            }
            .show()
    }

    private fun writeAmiiboFileCollection(amiiboList: ArrayList<AmiiboFile?>) {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.gatt_write_confirm)
            .setPositiveButton(R.string.proceed) { dialog: DialogInterface, _: Int ->
                showProcessingNotice(true)
                amiiboList.forEachIndexed { i, file ->
                    fragmentHandler.postDelayed({
                        uploadAmiiboFile(file, i == amiiboList.size - 1)
                    }, chunkTimeout * i)
                }
                onBottomSheetChanged(SHEET.MENU)
                settings.removeChangeListener(writeTagAdapter)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog: DialogInterface, _: Int ->
                onBottomSheetChanged(SHEET.MENU)
                settings.removeChangeListener(writeTagAdapter)
                dialog.dismiss()
            }
            .show()
    }

    private fun uploadAmiiboData(amiiboData: AmiiboData?, complete: Boolean = true) {
        var amiibo: Amiibo? = null
        settings.amiiboManager?.let {
            try {
                val amiiboId = Amiibo.dataToId(amiiboData?.array)
                amiibo = it.amiibos[amiiboId]
                if (null == amiibo) amiibo = Amiibo(it, amiiboId, null, null)
            } catch (e: Exception) { Debug.warn(e) }
        }
        amiibo?.let {
            amiiboData?.array?.let { data ->
                when (deviceType) {
                    Nordic.DEVICE.BLUUP, Nordic.DEVICE.FLASK, Nordic.DEVICE.SLIDE -> {
                        serviceGatt?.uploadAmiiboFile(
                                data, it, gattAdapter?.getDuplicates(it) ?: 0, complete
                        )
                    }
                    Nordic.DEVICE.PIXL, Nordic.DEVICE.LOOP, Nordic.DEVICE.LINK -> {
                        serviceGatt?.uploadAmiiboData(data)
                    }
                    Nordic.DEVICE.PUCK -> {
                        serviceGatt?.uploadSlotAmiibo(data, gattSlotCount.value - 1)
                    }
                    else -> {

                    }
                }
            }
        }
    }

    private fun uploadAmiiboFile(amiiboFile: AmiiboFile?, complete: Boolean = true) {
        amiiboFile?.let { file ->
            var amiibo: Amiibo? = null
            settings.amiiboManager?.let {
                try {
                    val amiiboId = Amiibo.dataToId(file.data)
                    amiibo = it.amiibos[amiiboId]
                    if (null == amiibo) amiibo = Amiibo(it, amiiboId, null, null)
                } catch (e: Exception) { Debug.warn(e) }
            }
            amiibo?.let {
                file.data?.let { data ->
                    when (deviceType) {
                        Nordic.DEVICE.BLUUP, Nordic.DEVICE.FLASK, Nordic.DEVICE.SLIDE -> {
                            serviceGatt?.uploadAmiiboFile(
                                    data, it, gattAdapter?.getDuplicates(it) ?: 0, complete
                            )
                        }
                        Nordic.DEVICE.PIXL, Nordic.DEVICE.LOOP, Nordic.DEVICE.LINK -> {
                            serviceGatt?.uploadAmiiboData(data)
                        }
                        Nordic.DEVICE.PUCK -> {
                            serviceGatt?.uploadSlotAmiibo(data, gattSlotCount.value - 1)
                        }
                        else -> {

                        }
                    }
                }
            }
        }
    }

    private fun setBottomSheetHidden(hidden: Boolean) {
        bottomSheet?.isHideable = hidden
        if (hidden)
            bottomSheet?.state = BottomSheetBehavior.STATE_HIDDEN
    }

    private fun dismissSnackbarNotice(finite: Boolean = false) {
        if (finite) noticeState = STATE.NONE
        if (statusBar?.isShown == true) statusBar?.dismiss()
    }

    private fun showScanningNotice() {
        dismissSnackbarNotice()
        noticeState = STATE.SCANNING
        if (isFragmentVisible) {
            statusBar = IconifiedSnackbar(requireActivity()).buildSnackbar(
                R.string.gatt_scanning,
                R.drawable.ic_bluetooth_searching_24dp, Snackbar.LENGTH_INDEFINITE
            ).also {
                it.show()
                it.view.keepScreenOn = true
            }
        }
    }

    private fun showConnectionNotice() {
        dismissSnackbarNotice()
        noticeState = STATE.CONNECT
        if (isFragmentVisible) {
            statusBar = IconifiedSnackbar(requireActivity()).buildSnackbar(
                R.string.gatt_located,
                R.drawable.ic_bluup_labs_24dp, Snackbar.LENGTH_INDEFINITE
            ).also {
                it.show()
                it.view.keepScreenOn = true
            }
        }
    }

    private fun showDisconnectNotice() {
        dismissSnackbarNotice()
        noticeState = STATE.MISSING
        if (isFragmentVisible) {
            statusBar = IconifiedSnackbar(requireActivity()).buildSnackbar(
                R.string.gatt_disconnect,
                R.drawable.ic_bluetooth_searching_24dp, Snackbar.LENGTH_INDEFINITE
            ).also { status ->
                status.setAction(R.string.scan) {
                    selectBluetoothDevice()
                    status.dismiss()
                }
                status.show()
            }
        }
    }

    private fun showProcessingNotice(upload: Boolean) {
        val builder = AlertDialog.Builder(requireContext())
        val view = layoutInflater.inflate(R.layout.dialog_process, null).apply {
            keepScreenOn = true
        }
        view.findViewById<TextView>(R.id.process_text).setText(
            if (upload) R.string.gatt_upload else R.string.gatt_remove
        )
        builder.setView(view)
        processDialog = builder.create().also {
            it.show()
            it.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    private fun showTimeoutNotice() {
        dismissSnackbarNotice()
        noticeState = STATE.TIMEOUT
        if (isFragmentVisible) {
            statusBar = IconifiedSnackbar(requireActivity()).buildSnackbar(
                R.string.gatt_missing,
                R.drawable.ic_bluup_labs_24dp,
                Snackbar.LENGTH_INDEFINITE
            ).also { status ->
                status.setAction(R.string.retry) {
                    selectBluetoothDevice()
                    status.dismiss()
                }
                status.show()
            }
        }
    }

    private fun startGattService() {
        val service = Intent(requireContext(), GattService::class.java)
        requireContext().startService(service)
        requireContext().bindService(service, gattServerConn, Context.BIND_AUTO_CREATE)
    }

    fun stopGattService() {
        if (null == context) return
        onBottomSheetChanged(SHEET.LOCKED)
        deviceAddress = null
        try {
            requireContext().unbindService(gattServerConn)
            requireContext().stopService(Intent(requireContext(), GattService::class.java))
        } catch (ignored: IllegalArgumentException) { }
    }

    fun disconnectService() {
        dismissSnackbarNotice(true)
        serviceGatt?.disconnect()
        stopGattService()
    }

    @SuppressLint("MissingPermission")
    private fun dismissGattDiscovery() {
        mBluetoothAdapter = mBluetoothAdapter
            ?: bluetoothHandler?.getBluetoothAdapter(requireContext())
        mBluetoothAdapter?.let { adapter ->
            if (!adapter.isEnabled) return
            if (Version.isLollipop) {
                scanCallbackNordicLP?.let {
                    adapter.bluetoothLeScanner.stopScan(it)
                    adapter.bluetoothLeScanner.flushPendingScanResults(it)
                }
                scanCallbackLegacyLP?.let {
                    adapter.bluetoothLeScanner.stopScan(it)
                    adapter.bluetoothLeScanner.flushPendingScanResults(it)
                }
            } else @Suppress("DEPRECATION") {
                scanCallbackNordic?.let { adapter.stopLeScan(it) }
                scanCallbackLegacy?.let { adapter.stopLeScan(it) }
            }
        }
    }

    private fun handleImageClicked(amiiboFile: AmiiboFile?) {
        amiiboFile?.let {
            this.startActivity(Intent(requireContext(), ImageActivity::class.java).apply {
                putExtras(Bundle().apply { putLong(NFCIntent.EXTRA_AMIIBO_ID, it.id) })
            })
        }
    }

    private val isBluetoothEnabled: Boolean
        get() {
            if (mBluetoothAdapter?.isEnabled == true) return true
            context?.run {
                bluetoothHandler = bluetoothHandler ?: BluetoothHandler(
                    this, requireActivity().activityResultRegistry,
                    this@GattSlotFragment
                )
                bluetoothHandler?.requestPermissions(requireActivity())
            } ?: fragmentHandler.postDelayed({ isBluetoothEnabled }, 125)
            return false
        }

    fun delayedBluetoothEnable() {
        fragmentHandler.postDelayed({ isBluetoothEnabled }, 125)
    }

    override fun onPause() {
        isFragmentVisible = false
        dismissSnackbarNotice()
        if (noticeState == STATE.SCANNING) dismissGattDiscovery()
        super.onPause()
    }

    override fun onDestroy() {
        try {
            dismissGattDiscovery()
        } catch (ignored: NullPointerException) { }
        disconnectService()
        bluetoothHandler?.unregisterResultContracts()
        super.onDestroy()
    }

    private fun onFragmentLoaded() {
        if (statusBar?.isShown != true) {
            fragmentHandler.postDelayed({
                when (noticeState) {
                    STATE.SCANNING, STATE.TIMEOUT -> {
                        if (isBluetoothEnabled) {
                            showScanningNotice()
                            selectBluetoothDevice()
                        }
                    }
                    STATE.CONNECT -> showConnectionNotice()
                    STATE.MISSING -> showDisconnectNotice()
                    else -> {}
                }
            }, TagMo.uiDelay.toLong())
            setBottomSheetHidden(false)
            onBottomSheetChanged(if (null == deviceAddress) SHEET.LOCKED else SHEET.MENU)
        }
    }

    override fun onResume() {
        isFragmentVisible = true
        super.onResume()
        onFragmentLoaded()
    }

    override fun onAmiiboClicked(amiibo: Amiibo?, position: Int) {
        getActiveAmiibo(amiibo, amiiboCard)
        onBottomSheetChanged(SHEET.AMIIBO)
        bottomSheet?.state = BottomSheetBehavior.STATE_EXPANDED
        if (amiibo is BluupTag) {
            val amiiboName = amiibo.bluupName ?: amiibo.name
            toolbar?.menu?.findItem(R.id.mnu_backup)?.isVisible = false
            toolbar?.setOnMenuItemClickListener { item: MenuItem ->
                if (item.itemId == R.id.mnu_activate) {
                    when (deviceType) {
                        Nordic.DEVICE.BLUUP, Nordic.DEVICE.FLASK, Nordic.DEVICE.SLIDE -> {
                            serviceGatt?.setActiveAmiibo(amiiboName, amiibo.bluupTail)
                        }
                        Nordic.DEVICE.PIXL, Nordic.DEVICE.LOOP, Nordic.DEVICE.LINK -> {

                        }
                        Nordic.DEVICE.PUCK -> {
                            serviceGatt?.setActiveSlot(position)
                        }
                        else -> {

                        }
                    }
                    return@setOnMenuItemClickListener true
                } else if (item.itemId == R.id.mnu_delete) {
                    when (deviceType) {
                        Nordic.DEVICE.BLUUP, Nordic.DEVICE.FLASK, Nordic.DEVICE.SLIDE -> {
                            serviceGatt?.deleteAmiibo(amiiboName, amiibo.bluupTail)
                        }
                        Nordic.DEVICE.PIXL, Nordic.DEVICE.LOOP, Nordic.DEVICE.LINK -> {

                        }
                        Nordic.DEVICE.PUCK -> {

                        }
                        else -> {

                        }
                    }
                    bottomSheet?.state = BottomSheetBehavior.STATE_COLLAPSED
                    return@setOnMenuItemClickListener true
                }
                false
            }
        } else amiibo?.let {
            val amiiboName = it.bluupName ?: it.name
            toolbar?.menu?.findItem(R.id.mnu_backup)?.isVisible = true
            toolbar?.setOnMenuItemClickListener { item: MenuItem ->
                when (item.itemId) {
                    R.id.mnu_activate -> {
                        when (deviceType) {
                            Nordic.DEVICE.BLUUP, Nordic.DEVICE.FLASK, Nordic.DEVICE.SLIDE -> {
                                serviceGatt?.setActiveAmiibo(amiiboName, it.bluupTail)
                            }
                            Nordic.DEVICE.PIXL, Nordic.DEVICE.LOOP, Nordic.DEVICE.LINK -> {

                            }
                            Nordic.DEVICE.PUCK -> {
                                serviceGatt?.setActiveSlot(position)
                            }
                            else -> {

                            }
                        }
                        return@setOnMenuItemClickListener true
                    }
                    R.id.mnu_delete -> {
                        when (deviceType) {
                            Nordic.DEVICE.BLUUP, Nordic.DEVICE.FLASK, Nordic.DEVICE.SLIDE -> {
                                serviceGatt?.deleteAmiibo(amiiboName, it.bluupTail)
                            }
                            Nordic.DEVICE.PIXL, Nordic.DEVICE.LOOP, Nordic.DEVICE.LINK -> {

                            }
                            Nordic.DEVICE.PUCK -> {

                            }
                            else -> {

                            }
                        }
                        bottomSheet?.state = BottomSheetBehavior.STATE_COLLAPSED
                        return@setOnMenuItemClickListener true
                    }
                    R.id.mnu_backup -> {
                        when (deviceType) {
                            Nordic.DEVICE.BLUUP, Nordic.DEVICE.FLASK, Nordic.DEVICE.SLIDE -> {
                                serviceGatt?.downloadAmiibo(amiiboName, it.bluupTail)
                            }
                            Nordic.DEVICE.PIXL, Nordic.DEVICE.LOOP, Nordic.DEVICE.LINK -> {

                            }
                            Nordic.DEVICE.PUCK -> {

                            }
                            else -> {

                            }
                        }
                        bottomSheet?.state = BottomSheetBehavior.STATE_COLLAPSED
                        return@setOnMenuItemClickListener true
                    }
                    else -> false
                }
            }
        }
    }

    override fun onAmiiboImageClicked(amiibo: Amiibo?) {
        amiibo?.let {
            this.startActivity(Intent(requireContext(), ImageActivity::class.java)
                .putExtras(Bundle().apply { putLong(NFCIntent.EXTRA_AMIIBO_ID, it.id) })
            )
        }
    }

    override fun onPermissionsFailed() {
        this.mBluetoothAdapter = null
        setBottomSheetHidden(true)
        Toasty(requireActivity()).Long(R.string.fail_permissions)
    }

    override fun onAdapterMissing() {
        this.mBluetoothAdapter = null
        noticeState = STATE.MISSING
        setBottomSheetHidden(true)
        Toasty(requireActivity()).Long(R.string.fail_bluetooth_adapter)
    }

    override fun onAdapterRestricted() {
        delayedBluetoothEnable()
    }

    override fun onAdapterEnabled(adapter: BluetoothAdapter?) {
        this.mBluetoothAdapter = adapter
        setBottomSheetHidden(false)
        selectBluetoothDevice()
    }

    private var gattServerConn: ServiceConnection = object : ServiceConnection {
        var isServiceDiscovered = false
        override fun onServiceConnected(component: ComponentName, binder: IBinder) {
            val localBinder = binder as GattService.LocalBinder
            serviceGatt = localBinder.service.apply {
                if (initialize() && connect(deviceAddress)) {
                    serviceType = deviceType
                    maxSlotCount = when (serviceType) {
                        Nordic.DEVICE.FLASK -> 85
                        Nordic.DEVICE.SLIDE -> 40
                        Nordic.DEVICE.LINK -> 1
                        Nordic.DEVICE.LOOP -> 1
                        Nordic.DEVICE.PUCK -> 32
                        else -> 50
                    }
                    setListener(object : GattService.BluetoothGattListener {
                        override fun onBluupServicesDiscovered() {
                            isServiceDiscovered = true
                            onBottomSheetChanged(SHEET.MENU)
                            requireView().post {
                                gattSlotCount.maxValue = maxSlotCount
                                screenOptions?.isVisible = true
                                createBlank?.isVisible = true
                                requireView().findViewById<TextView>(R.id.hardware_info).text = deviceProfile
                            }
                            try {
                                setCharacteristicRX()
                                deviceAmiibo
                            } catch (uoe: UnsupportedOperationException) {
                                Debug.verbose(uoe)
                                disconnectService()
                                Toasty(requireContext()).Short(R.string.device_invalid)
                            }
                        }

                        override fun onBluupStatusChanged(jsonObject: JSONObject?) {
                            processDialog?.let {
                                if (it.isShowing) it.dismiss()
                            }
                            deviceAmiibo
                        }

                        override fun onBluupListRetrieved(jsonArray: JSONArray) {
                            currentCount = jsonArray.length()
                            val bluupAmiibos: ArrayList<Amiibo?> = arrayListOf()
                            for (i in 0 until currentCount) {
                                try {
                                    val amiibo = getAmiiboFromTail(
                                        jsonArray.getString(i).split("|")
                                    )
                                    bluupAmiibos.add(amiibo)
                                } catch (ex: JSONException) {
                                    Debug.warn(ex)
                                } catch (ex: NullPointerException) {
                                    Debug.warn(ex)
                                }
                            }
                            settings.removeChangeListener(gattAdapter)
                            gattAdapter = GattSlotAdapter(
                                settings, this@GattSlotFragment
                            ).also {
                                it.setGattAmiibo(bluupAmiibos)
                                dismissSnackbarNotice(true)
                                requireView().post {
                                    bluupContent?.adapter = it
                                    settings.addChangeListener(it)
                                }
                                if (currentCount > 0) {
                                    activeAmiibo
                                    requireView().post {
                                        it.notifyItemRangeInserted(0, currentCount)
                                    }
                                } else {
                                    amiiboTile?.isInvisible = true
                                    gattButtonState
                                }
                            }
                        }

                        override fun onBluupRangeRetrieved(jsonArray: JSONArray) {
                            val bluupAmiibos: ArrayList<Amiibo?> = arrayListOf()
                            for (i in 0 until jsonArray.length()) {
                                try {
                                    val amiibo = getAmiiboFromTail(
                                        jsonArray.getString(i).split("|")
                                    )
                                    bluupAmiibos.add(amiibo)
                                } catch (ex: JSONException) {
                                    Debug.warn(ex)
                                } catch (ex: NullPointerException) {
                                    Debug.warn(ex)
                                }
                            }
                            gattAdapter?.run {
                                addGattAmiibo(bluupAmiibos)
                                requireView().post {
                                    notifyItemRangeInserted(currentCount, bluupAmiibos.size)
                                }
                                currentCount = itemCount
                            }
                        }

                        override fun onBluupActiveChanged(jsonObject: JSONObject?) {
                            if (null == jsonObject) return
                            try {
                                val name = jsonObject.getString("name")
                                if ("undefined" == name) {
                                    resetActiveSlot()
                                    return
                                }
                                val amiibo = getAmiiboFromTail(name.split("|"))
                                val index = jsonObject.getString("index")
                                getActiveAmiibo(amiibo, amiiboTile)
                                if (bottomSheet?.state == BottomSheetBehavior.STATE_COLLAPSED)
                                    getActiveAmiibo(amiibo, amiiboCard)
                                prefs.gattActiveSlot(index.toInt())
                                gattButtonState
                                requireView().post {
                                    gattStats?.text =
                                        getString(R.string.gatt_count, index, currentCount)
                                }
                            } catch (ex: JSONException) {
                                Debug.warn(ex)
                            } catch (ex: NullPointerException) {
                                Debug.warn(ex)
                            }
                        }

                        override fun onBluupFilesDownload(dataString: String) {
                            Debug.info(this.javaClass, dataString)
                            try {
                                val tagData = dataString.toByteArray()
                            } catch (e: Exception) { e.printStackTrace() }
                            Toasty(requireActivity()).Short(R.string.fail_firmware_api)
                        }

                        override fun onBluupProcessFinish() {
                            processDialog?.let {
                                if (it.isShowing) it.dismiss()
                            }
                        }

                        override fun onBluupConnectionLost() {
                            fragmentHandler.postDelayed(
                                { showDisconnectNotice() }, TagMo.uiDelay.toLong()
                            )
                            bottomSheet?.state = BottomSheetBehavior.STATE_COLLAPSED
                            stopGattService()
                        }

                        override fun onPixlServicesDiscovered() {
                            isServiceDiscovered = true
                            onBottomSheetChanged(SHEET.MENU)
                            requireView().post {
                                gattSlotCount.maxValue = maxSlotCount
                                screenOptions?.isGone = true
                                createBlank?.isGone = true
                                requireView().findViewById<TextView>(R.id.hardware_info).text = deviceProfile
                            }
                            try {
                                setCharacteristicRX()
                                deviceAmiibo
                            } catch (uoe: UnsupportedOperationException) {
                                Debug.verbose(uoe)
                                disconnectService()
                                Toasty(requireContext()).Short(R.string.device_invalid)
                            }
                        }

                        override fun onPixlStatusChanged(jsonObject: JSONObject?) {
                            processDialog?.let {
                                if (it.isShowing) it.dismiss()
                            }
                            deviceAmiibo
                        }

                        @SuppressLint("SetTextI18n", "CutPasteId")
                        override fun onPixlDataReceived(result: String?) {
                            processDialog?.let {
                                if (it.isShowing) it.dismiss()
                            }
                            requireView().post {
                                requireView().findViewById<TextView>(R.id.gatt_readout).text =
                                        "${requireView().findViewById<TextView>(R.id.gatt_readout).text}$result"
                            }
                        }

                        override fun onPixlActiveChanged(jsonObject: JSONObject?) {
                            if (null == jsonObject) return
                            try {
                                val name = jsonObject.getString("name")
                                if ("undefined" == name) {
                                    resetActiveSlot()
                                    return
                                }
                                val amiibo = getAmiiboFromTail(name.split("|"))
                                val index = jsonObject.getString("index")
                                getActiveAmiibo(amiibo, amiiboTile)
                                if (bottomSheet?.state == BottomSheetBehavior.STATE_COLLAPSED)
                                    getActiveAmiibo(amiibo, amiiboCard)
                                prefs.gattActiveSlot(index.toInt())
                                gattButtonState
                                requireView().post {
                                    gattStats?.text =
                                            getString(R.string.gatt_count, index, currentCount)
                                }
                            } catch (ex: JSONException) {
                                Debug.warn(ex)
                            } catch (ex: NullPointerException) {
                                Debug.warn(ex)
                            }
                        }

                        override fun onPixlFilesDownload(dataString: String) {
                            Debug.info(this.javaClass, dataString)
                            try {
                                val tagData = dataString.toByteArray()
                            } catch (e: Exception) { e.printStackTrace() }
                            Toasty(requireActivity()).Short(R.string.fail_firmware_api)
                        }

                        override fun onPixlProcessFinish() {
                            processDialog?.let {
                                if (it.isShowing) it.dismiss()
                            }
                        }

                        override fun onPixlConnectionLost() {
                            fragmentHandler.postDelayed(
                                    { showDisconnectNotice() }, TagMo.uiDelay.toLong()
                            )
                            bottomSheet?.state = BottomSheetBehavior.STATE_COLLAPSED
                            stopGattService()
                        }

                        override fun onPuckServicesDiscovered() {
                            isServiceDiscovered = true
                            onBottomSheetChanged(SHEET.MENU)
                            requireView().post {
                                gattSlotCount.maxValue = maxSlotCount
                                screenOptions?.isGone = true
                                createBlank?.isGone = true
                                requireView().findViewById<TextView>(R.id.hardware_info).text = deviceProfile
                            }
                            try {
                                setPuckServicesUUID()
                                deviceDetails
                            } catch (uoe: UnsupportedOperationException) {
                                Debug.verbose(uoe)
                                disconnectService()
                                Toasty(requireContext()).Short(R.string.device_invalid)
                            }
                        }

                        override fun onPuckDeviceProfile(slotCount: Int) {
                            maxSlotCount = slotCount
                            requireView().post {
                                gattSlotCount.maxValue = slotCount
                            }
                            deviceAmiibo
                        }

                        override fun onPuckActiveChanged(slot: Int) {
                            gattAdapter?.run {
                                val amiibo = getItem(slot)
                                getActiveAmiibo(amiibo, amiiboTile)
                                if (bottomSheet?.state == BottomSheetBehavior.STATE_COLLAPSED)
                                    getActiveAmiibo(amiibo, amiiboCard)
                                prefs.gattActiveSlot(slot)
                                gattButtonState
                                requireView().post {
                                    gattStats?.text = getString(
                                            R.string.gatt_count, slot.toString(), currentCount
                                    )
                                }
                            }
                        }

                        override fun onPuckListRetrieved(
                                slotData: ArrayList<ByteArray>, active: Int
                        ) {
                            currentCount = slotData.size
                            val puckAmiibos: ArrayList<Amiibo?> = arrayListOf()
                            slotData.forEach { bytes ->
                                puckAmiibos.add(getAmiiboFromSlice(bytes))
                            }
                            settings.removeChangeListener(gattAdapter)
                            gattAdapter = GattSlotAdapter(
                                    settings, this@GattSlotFragment
                            ).also {
                                it.setGattAmiibo(puckAmiibos)
                                dismissSnackbarNotice(true)
                                requireView().post {
                                    bluupContent?.adapter = it
                                    settings.addChangeListener(it)
                                }
                                if (currentCount > 0) {
                                    requireView().post {
                                        it.notifyItemRangeInserted(0, currentCount)
                                    }
                                    onPuckActiveChanged(active)
                                } else {
                                    amiiboTile?.isInvisible = true
                                    gattButtonState
                                }
                            }
                        }

                        override fun onPuckFilesDownload(tagData: ByteArray) {}

                        override fun onPuckProcessFinish() {
                            processDialog?.let {
                                if (it.isShowing) it.dismiss()
                            }
                        }

                        override fun onPuckConnectionLost() {
                            fragmentHandler.postDelayed(
                                    { showDisconnectNotice() }, TagMo.uiDelay.toLong()
                            )
                            bottomSheet?.state = BottomSheetBehavior.STATE_COLLAPSED
                            stopGattService()
                        }
                    })
                } else {
                    stopGattService()
                    Toasty(requireContext()).Short(R.string.device_invalid)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            stopGattService()
            if (!isServiceDiscovered) {
                showTimeoutNotice()
            }
        }
    }
}