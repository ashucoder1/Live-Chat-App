package com.example.livechat

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.core.text.isDigitsOnly
import androidx.lifecycle.ViewModel
import androidx.navigation.NavController
import com.example.livechat.data.CHATS
import com.example.livechat.data.ChatData
import com.example.livechat.data.ChatUser
import com.example.livechat.data.Event
import com.example.livechat.data.MESSAGE
import com.example.livechat.data.Message
import com.example.livechat.data.STATUS
import com.example.livechat.data.Status
import com.example.livechat.data.USER_NODE
import com.example.livechat.data.UserData
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Filter
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.toObject
import com.google.firebase.firestore.toObjects
import com.google.firebase.storage.FirebaseStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.lang.Exception
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class LCViewModel @Inject constructor(
    val auth: FirebaseAuth,
    val db: FirebaseFirestore,
    val storage: FirebaseStorage
) : ViewModel() {

    var inProcessChats = mutableStateOf(false)
    var inProcess = mutableStateOf(false)
    val eventMutableState = mutableStateOf<Event<String>?>(null)
    var signIn = mutableStateOf(false)
    val userData = mutableStateOf<UserData?>(null)
    val chats = mutableStateOf<List<ChatData>>(listOf())
    val chatMessages= mutableStateOf<List<Message>>(listOf())
    val inProgressChatMessage= mutableStateOf(false)
    var currentChatMessageListener:ListenerRegistration?=null

    val status= mutableStateOf<List<Status>>(listOf())
    var inProgressStatus= mutableStateOf(false)


    init {
        val currentUser = auth.currentUser
        signIn.value = currentUser != null
        currentUser?.uid?.let {
            getUserData(it)
        }
    }

    fun populateMessages(chatId:String){
        inProgressChatMessage.value=true
        currentChatMessageListener=db.collection(CHATS).document(chatId).collection(MESSAGE)
            .addSnapshotListener { value, error ->
                if (error!=null) {
                    handleException(error)
                }
                if (value!=null){
                    chatMessages.value=value.documents.mapNotNull {
                        it.toObject<Message>()
                    }.sortedBy { it.timeStamp }
                    inProgressChatMessage.value=false
                }
            }
    }

    fun depopulateMessage(){
        chatMessages.value= listOf()
        currentChatMessageListener=null
    }

    fun populateChats(){
        inProcessChats.value=true
        db.collection(CHATS).where(
            Filter.or(
                Filter.equalTo("user1.userId",userData.value?.userId),
                Filter.equalTo("user2.userId",userData.value?.userId)
            )
        ).addSnapshotListener {
                              value, error ->
            if (error!=null){
                handleException(error)
            }
            if (value!=null){
                chats.value=value.documents.mapNotNull {
                    it.toObject<ChatData>()
                }
                inProcessChats.value=false
            }

        }

    }


    fun onSendReply(chatID:String,message:String){
        val time=Calendar.getInstance().time.toString()
        var msg=Message(userData.value?.userId,message,time)
        db.collection(CHATS).document(chatID).collection(MESSAGE).document().set(msg)
    }


    fun signUp(name: String, number: String, email: String, password: String) {
        inProcess.value = true
        if (name.isEmpty() or password.isEmpty() or email.isEmpty() or password.isEmpty()) {
            handleException(customMessage = "Please fill all fields")
            return
        }

        inProcess.value = true
        db.collection(USER_NODE).whereEqualTo("number", number).get().addOnSuccessListener {
            if (it.isEmpty) {
                auth.createUserWithEmailAndPassword(email, password).addOnCompleteListener {
                    if (it.isSuccessful) {
                        signIn.value = true
                        createOrUpdateProfile(name, number)

                    } else {
                        handleException(it.exception, customMessage = "Sign Up Failed")
                    }
                }
            } else {
                handleException(customMessage = "number already exist")
                inProcess.value = false
            }
        }


    }

    fun logIn(email: String, password: String) {
        if (email.isEmpty() or password.isEmpty()) {
            handleException(customMessage = "please fill all fields")
            return
        } else {
            inProcess.value = true
            auth.signInWithEmailAndPassword(email, password).addOnCompleteListener {
                if (it.isSuccessful) {
                    signIn.value = true
                    inProcess.value = false
                    auth.currentUser?.uid?.let {
                        getUserData(uid = it)
                    }
                } else {
                    handleException(
                        exception = it.exception,
                        customMessage = "wrong email or password"
                    )
                }
            }
        }
    }

    fun uploadProfileImage(uri: Uri) {
        uploadImage(uri) {
            //Log.d("hello", "uploadProfileImage:1 ")
            createOrUpdateProfile(imageurl = it.toString())
        }
    }

    fun uploadImage(uri: Uri, onSuccess: (Uri) -> Unit) {
        inProcess.value = true
        val storageRef = storage.reference
        val uuid = UUID.randomUUID()
        val imageRef = storageRef.child("images/$uuid")
        val uploadTask = imageRef.putFile(uri)
        uploadTask.addOnSuccessListener {
            val result = it.metadata?.reference?.downloadUrl

            result?.addOnSuccessListener(onSuccess)
            inProcess.value = false
            //Log.d("hello", "uploadProfileImage:2 ")

        }
            .addOnFailureListener {
                handleException(it)
            }
    }


    fun createOrUpdateProfile(
        name: String? = null,
        number: String? = null,
        imageurl: String? = null
    ) {
        var uid = auth.currentUser?.uid
        var userData = UserData(
            userId = uid,
            name = name ?: userData.value?.name,
            number = number ?: userData.value?.number,
            imageUrl = imageurl ?: userData.value?.imageUrl

        )
        uid?.let {
            inProcess.value = true
            db.collection(USER_NODE).document(uid).get().addOnSuccessListener {
                if (it.exists()) {
                    //update user data
                    Log.d("hello", "uploadProfileImage:3 ")
                    // Update only the fields that need to be updated
                    val updateData = mutableMapOf<String, Any>()
                    imageurl?.let { newImageUrl ->
                        updateData["imageUrl"] = newImageUrl
                    }
                    db.collection(USER_NODE).document(uid).update(updateData)
                        .addOnSuccessListener {
                            inProcess.value = false
                            getUserData(uid)
                            Log.d("hello", "Profile image updated successfully.")
                        }
                        .addOnFailureListener { exception ->
                            handleException(exception, "Failed to update user data")
                            Log.d("hello", "Profile image failed successfully.")

                        }




                } else {
                    db.collection(USER_NODE).document(uid).set(userData)
                    inProcess.value = false
                    getUserData(uid)
                    //Log.d("hello", "uploadProfileImage:4 ")

                }
            }
                .addOnFailureListener {
                    handleException(it, "cannot retrieve User")
                }
        }

    }

    private fun getUserData(uid: String) {
        inProcess.value = true
        db.collection(USER_NODE).document(uid).addSnapshotListener { value, error ->
            if (error != null) {
                handleException(error, "cannot retrieve User")
                //Log.d("hello", "uploadProfileImage:5")

            }
            if (value != null) {
                //Log.d("hello", "uploadProfileImage:6")

                val user = value.toObject<UserData>()
                userData.value = user
                inProcess.value = false
                populateChats()
                populateStatuses()
                //Log.d("hello", "uploadProfileImage:7")

            }
        }
    }

    fun handleException(exception: Exception? = null, customMessage: String = "") {
        Log.e("LiveChatApp", "live chat exception: ", exception)
        exception?.printStackTrace()
        val errorMsg = exception?.localizedMessage ?: ""
        val message = if (customMessage.isNullOrEmpty()) errorMsg else customMessage

        eventMutableState.value = Event(message)
        inProcess.value = false
    }

    fun logout() {
        auth.signOut()
        signIn.value = false
        userData.value = null
        depopulateMessage()
        currentChatMessageListener=null
        eventMutableState.value = Event("Logged Out")
    }

    fun onAddChat(number: String) {
        if (number.isEmpty() or !number.isDigitsOnly()) {
            handleException(customMessage = "number must be digit only")
        } else {
            db.collection(CHATS).where(
                Filter.or(
                    Filter.and(
                        Filter.equalTo("user1.number", number),
                        Filter.equalTo("user2.number", userData.value?.number)
                    ),

                    Filter.and(
                        Filter.equalTo("user1.number", userData.value?.number),
                        Filter.equalTo("user2.number", number)
                    )
                )
            ).get().addOnSuccessListener {
                if (it.isEmpty) {
                    db.collection(USER_NODE).whereEqualTo("number", number).get()
                        .addOnSuccessListener {
                            if (it.isEmpty) {
                                handleException(customMessage = "number not found")
                            } else {
                                val chatPartner = it.toObjects<UserData>()[0]
                                val id = db.collection(CHATS).document().id
                                val chat = ChatData(
                                    chatId = id,
                                    ChatUser(
                                        userData.value?.userId,
                                        userData.value?.name,
                                        userData.value?.imageUrl,
                                        userData.value?.number
                                    ),
                                    ChatUser(
                                        chatPartner.userId,
                                        chatPartner.name,
                                        chatPartner.imageUrl,
                                        chatPartner.number
                                    )
                                )
                                db.collection(CHATS).document(id).set(chat)
                            }
                        }
                        .addOnFailureListener {
                            handleException(it)
                        }
                }
                else {
                    handleException(customMessage = "Chats already exist")
                }
            }
        }
    }

    fun uploadStatus(uri: Uri){
        uploadImage(uri){
        createStatus(it.toString())
        }
    }
    fun createStatus(imageUrl:String?){
        val newStatus=Status(
            ChatUser(
                userData.value?.userId,
                userData.value?.name,
                userData.value?.imageUrl,
                userData.value?.number
            ),
            imageUrl,
            System.currentTimeMillis()
        )

        db.collection(STATUS).document().set(newStatus)
    }

    fun populateStatuses(){
        var timeDelta=24L *60 *60*1000
        val cutOff=System.currentTimeMillis()-timeDelta
        inProgressStatus.value=true
        db.collection(CHATS).where(
            Filter.or(
                Filter.equalTo("user1.userId",userData.value?.userId),
                Filter.equalTo("user2.userId",userData.value?.userId)
            )
        ).addSnapshotListener{
            value,error->
            if(error!=null){
                handleException(error)

            }
            if (value!=null){

                val currentConnections= arrayListOf(userData.value?.userId)

                val chats=value.toObjects<ChatData>()
                chats.forEach {
                    chat->
                    if(chat.user1.userId==userData.value?.userId){
                        currentConnections.add(chat.user2.userId)
                    }else{
                        currentConnections.add(chat.user1.userId)
                    }
                }

                db.collection(STATUS).whereGreaterThan("timeStamp",cutOff).whereIn("user.userId",currentConnections)
                    .addSnapshotListener { value, error ->
                        if(error!=null){
                            handleException(error)

                        }
                        if(value!=null){

                            status.value=value.toObjects()
                            inProgressStatus.value=false
                        }
                    }
            }
        }
    }



}

