import android.app.Service
import android.os.IBinder
import android.content.Intent

class ScanerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}