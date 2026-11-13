package com.example.casha.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.ui.platform.LocalContext
import com.example.casha.data.ReceiptScanner
import java.math.BigDecimal
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.casha.data.FinanceStore
import com.example.casha.domain.CashEntry
import com.example.casha.domain.MoneyAccount
import com.example.casha.domain.parseMoney
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun QuickExpenseDialog(finance:FinanceStore,dismiss:()->Unit,saved:(CashEntry)->Unit) {
    val accounts = remember { finance.moneyAccounts() }
    var selectedAccount by rememberSaveable { mutableStateOf(accounts.firstOrNull { !it.isSavings }?.name ?: MoneyAccount.DEFAULT_CASH) }
    var amount by rememberSaveable {mutableStateOf("")}
    var category by rememberSaveable {mutableStateOf("")}
    var note by rememberSaveable {mutableStateOf("")}
    var date by rememberSaveable {mutableStateOf(LocalDate.now().toString())}
    var changeDate by rememberSaveable {mutableStateOf(false)}
    var receiptPath by rememberSaveable {mutableStateOf<String?>(null)}
    var receiptReference by rememberSaveable {mutableStateOf<String?>(null)}
    val id=rememberSaveable {UUID.randomUUID().toString()}
    var error by remember {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(false)}
    val focus=remember {FocusRequester()}
    val keyboard=LocalSoftwareKeyboardController.current
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    var pendingCameraPath by rememberSaveable {mutableStateOf<String?>(null)}
    var scanNote by remember {mutableStateOf<String?>(null)}
    fun scan(uri:Uri,cleanup:java.io.File?=null) {
        busy=true;error=null;keyboard?.hide()
        scope.launch {
            val result=withContext(Dispatchers.IO) {
                try {runCatching {ReceiptScanner.scan(context,uri,finance.scope)}} finally {cleanup?.delete()}
            }
            result.onSuccess {receipt->
                receipt.amount?.let {amount=BigDecimal.valueOf(it,2).toPlainString()}
                receipt.date?.takeUnless {it.isAfter(LocalDate.now())}?.let {date=it.toString();changeDate=true}
                note=receipt.reference?.let {"Receipt · Ref $it"}?.take(160)?:"Receipt expense"
                scanNote="Receipt scanned. Check the amount and date before saving. ${receipt.note}"
                receiptPath=receipt.receipt
                receiptReference=receipt.reference
            }.onFailure {error=it.message?:"Could not read this receipt. You can enter it manually."}
            busy=false
        }
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {uri->if(uri!=null)scan(uri)}
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) {captured->
        val file=pendingCameraPath?.let {java.io.File(it)};pendingCameraPath=null
        if(captured&&file!=null&&file.exists())scan(FileProvider.getUriForFile(context,"${context.packageName}.files",file),file) else file?.delete()
    }
    fun save() {
        if(busy)return
        val value=parseMoney(amount);val day=runCatching {LocalDate.parse(date)}.getOrNull()
        if(value==null||day==null||day.isAfter(LocalDate.now())||day.year !in 1900..2200) {error="Enter a valid amount and a date no later than today.";return}
        val entry=CashEntry(if(receiptPath!=null)"receipt_$id" else id,false,value,day,listOf(category,note.trim()).filter {it.isNotEmpty()}.joinToString(" · ").take(200),channel=selectedAccount)
        busy=true;error=null
        scope.launch {
            val success=withContext(Dispatchers.IO){runCatching {if(receiptPath!=null)finance.recordReceiptExpense(com.example.casha.data.PaymentSuggestion(id,value,day,receiptReference,"Receipt OCR",receiptPath),entry,receiptReference) else finance.addCash(entry)}.getOrDefault(false)}
            busy=false
            if(success){keyboard?.hide();saved(entry)}else error="Could not save. Your entry is still here; try again."
        }
    }

    val activeAccent = if (category.isNotEmpty()) categoryColor(category) else MaterialTheme.colorScheme.tertiary
    PilotModal(
        onDismissRequest={if(!busy)dismiss()},
        category="QUICK EXPENSE",
        icon="Plus",
        accent=activeAccent,
        title={Text("Add expense")},
        text={
            LaunchedEffect(Unit){withFrameNanos {};focus.requestFocus();keyboard?.show()}
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max=440.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item {
                    OutlinedTextField(
                        value=amount,
                        onValueChange={amount=it;error=null},
                        label={Text("Amount (PHP)")},
                        modifier=Modifier.fillMaxWidth().focusRequester(focus),
                        singleLine=true,
                        enabled=!busy,
                        isError=error!=null,
                        shape=MaterialTheme.shapes.large,
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal,imeAction=ImeAction.Done),
                        keyboardActions=KeyboardActions(onDone={save()})
                    )
                    error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
                }
                item {
                    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text("Read a receipt",style=MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({keyboard?.hide();picker.launch("image/*")},enabled=!busy,modifier=Modifier.weight(1f)){
                                PilotIcon("Download",Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Import image")
                            }
                            OutlinedButton({
                                runCatching {ReceiptScanner.createCaptureTarget(context,finance.scope)}.onSuccess {(file,uri)->
                                    pendingCameraPath=file.path
                                    runCatching {camera.launch(uri)}.onFailure {file.delete();pendingCameraPath=null;error="No camera available. Import an image instead."}
                                }.onFailure {error="Could not open camera."}
                            },enabled=!busy,modifier=Modifier.weight(1f)){
                                PilotIcon("Camera",Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Take photo")
                            }
                        }
                        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                        scanNote?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
                        Text("On-device scanning. Nothing is saved until you confirm the expense.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text("Account / Wallet",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            accounts.forEach {acc->
                                FilterChip(
                                    selected=selectedAccount==acc.name,
                                    onClick={selectedAccount=acc.name},
                                    label={Text(acc.name)},
                                    leadingIcon={PilotIcon(acc.icon,Modifier.size(16.dp))},
                                    enabled=!busy
                                )
                            }
                        }
                        val isSavingsAccount=accounts.find {it.name==selectedAccount}?.isSavings==true
                        if(isSavingsAccount) {
                            Surface(color=MaterialTheme.colorScheme.primaryContainer.copy(alpha=0.45f),shape=RoundedCornerShape(8.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=0.3f))) {
                                Row(Modifier.padding(horizontal=10.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                    PilotIcon("Savings",Modifier.size(16.dp),tint=MaterialTheme.colorScheme.primary)
                                    Text("Savings account: stays protected and does not reduce your Safe to Spend allowance.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text("Category",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            listOf("Food","Transport","Bills","Shopping").forEach {label->
                                val chipColor=categoryColor(label)
                                FilterChip(
                                    selected=category==label,
                                    onClick={category=if(category==label)"" else label},
                                    label={Text(label)},
                                    enabled=!busy
                                )
                            }
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value=note,
                        onValueChange={note=it.take(160)},
                        label={Text("Note (optional)")},
                        modifier=Modifier.fillMaxWidth(),
                        singleLine=true,
                        enabled=!busy,
                        shape=MaterialTheme.shapes.large
                    )
                }
                item {
                    if(changeDate)DateEntryField(date,{date=it},"Expense date (YYYY-MM-DD)",enabled=!busy)
                    else TextButton({changeDate=true},enabled=!busy,shape=MaterialTheme.shapes.small){PilotIcon("Payday",Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("Today · Change date")}
                }
            }
        },
        confirmButton={Button({save()},enabled=!busy&&amount.isNotBlank(),shape=MaterialTheme.shapes.medium){
            PilotIcon("Check",Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if(busy)"Saving…" else "Save expense")
        }},
        dismissButton={TextButton({keyboard?.hide();dismiss()},enabled=!busy,shape=MaterialTheme.shapes.small){Text("Cancel")}}
    )
}
