package dev.thor.tools

/** A cleanup acknowledgement records actual release I/O, never merely the existence of a job. */
object GamepadCleanup {
    data class Receipt(val releaseAttempted: Boolean,val releaseAcknowledged: Boolean,val resourceClosed: Boolean,val error: Throwable?) {
        val complete get()=resourceClosed && (!releaseAttempted || releaseAcknowledged)
        fun data(): Map<String,Any?> = mapOf("released" to releaseAcknowledged,"releaseAttempted" to releaseAttempted,
            "releaseAcknowledged" to releaseAcknowledged,"resourceClosed" to resourceClosed,"cleanupComplete" to complete,
            "cleanupError" to error?.let {"${it.javaClass.simpleName}${if(it is ToolFailure)": ${it.message}" else ""}"})
    }
    fun finish(submitted: Boolean,release: ()->Unit,close: ()->Unit): Receipt {
        var acknowledged=false;var closed=false;var failure: Throwable?=null
        if(submitted)try {release();acknowledged=true}catch(t:Throwable){failure=t}
        // A failed release must not leak the original device fd or open a replacement device.
        try {close();closed=true}catch(t:Throwable){if(failure==null)failure=t}
        return Receipt(submitted,acknowledged,closed,failure)
    }
}
