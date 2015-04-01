package net.philadams.keppi;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.content.FileProvider;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.UUID;

/**
 * TODO:philadams actually receive data from the Keppi
 */
public class MainActivity extends Activity {

  public static final String TAG = MainActivity.class.getSimpleName();
  private final String LOG_FNAME = "net.philadams.keppi.log.csv";
  private final String[] EMAIL_RECIPIENTS = { "philadams.net@gmail.com" };

  // Intent requests
  private final int REQUEST_ENABLE_BT = 1;

  // misc constants
  private static final long MAX_SCAN_PERIOD = 3000;

  // Bluetooth state
  final private static int STATE_BLUETOOTH_OFF = 1;
  final private static int STATE_DISCONNECTED = 2;
  final private static int STATE_CONNECTING = 3;
  final private static int STATE_CONNECTED = 4;
  private int state;

  private boolean scanning;

  private BluetoothAdapter bluetoothAdapter;
  private BluetoothDevice bluetoothDevice;

  private RFduinoService rfduinoService;

  private Handler handler;

  private ListView receivedDataListView;
  private ArrayAdapter receivedDataAdapter;
  private ArrayList<String> receivedData;
  private TextView scanStatusText;
  private Button scanButton;
  private TextView deviceInfoText;
  private TextView connectionStatusText;
  private Button connectButton;
  private Button clearButton;
  private KeppiSliderNumberedView slider;

  ////////////////////////
  // BroadcastReceivers //
  ////////////////////////

  private final BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0);
      if (state == BluetoothAdapter.STATE_ON) {
        upgradeState(STATE_DISCONNECTED);
      } else if (state == BluetoothAdapter.STATE_OFF) {
        downgradeState(STATE_BLUETOOTH_OFF);
      }
    }
  };

  private final BroadcastReceiver scanModeReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      //scanning = (bluetoothAdapter.getScanMode() != BluetoothAdapter.SCAN_MODE_NONE);
      //updateUi();
    }
  };

  private final BroadcastReceiver rfduinoReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      final String action = intent.getAction();
      Log.d(TAG, String.format("rfduinoReceiver received: %s", action));
      if (RFduinoService.ACTION_CONNECTED.equals(action)) {
        upgradeState(STATE_CONNECTED);
      } else if (RFduinoService.ACTION_DISCONNECTED.equals(action)) {
        downgradeState(STATE_DISCONNECTED);
      } else if (RFduinoService.ACTION_DATA_AVAILABLE.equals(action)) {
        addData(intent.getByteArrayExtra(RFduinoService.EXTRA_DATA));
      }
    }
  };

  //////////////
  // Services //
  //////////////

  private final ServiceConnection rfduinoServiceConnection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
      rfduinoService = ((RFduinoService.LocalBinder) service).getService();
      if (rfduinoService.initialize()) {
        if (rfduinoService.connect(bluetoothDevice.getAddress())) {
          upgradeState(STATE_CONNECTING);
        }
      }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
      rfduinoService = null;
      downgradeState(STATE_DISCONNECTED);
    }
  };

  ////////////////////////////
  // Activity state changes //
  ////////////////////////////

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    // init various variables
    handler = new Handler();
    receivedDataListView = (ListView) findViewById(R.id.received_data_list);
    receivedData = new ArrayList<String>();
    receivedDataAdapter =
        new ArrayAdapter<String>(this, android.R.layout.simple_expandable_list_item_1,
            android.R.id.text1, receivedData);
    receivedDataListView.setAdapter(receivedDataAdapter);
    scanStatusText = (TextView) findViewById(R.id.scanStatus);
    scanButton = (Button) findViewById(R.id.scan);
    deviceInfoText = (TextView) findViewById(R.id.deviceInfo);
    connectionStatusText = (TextView) findViewById(R.id.connectionStatus);
    connectButton = (Button) findViewById(R.id.connect);
    slider = (KeppiSliderNumberedView) findViewById(R.id.keppi_slider_view);

    // ensure bluetooth on, with explicit user permission if it's not
    ensureBluetoothEnabled();

    // scanning for RFDuino
    scanButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        ensureBluetoothEnabled();
        scanForRFduinoDevice(true);
      }
    });

    // connecting to the device
    connectButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        v.setEnabled(false);
        connectionStatusText.setText("Connecting...");
        Intent rfduinoIntent = new Intent(MainActivity.this, RFduinoService.class);
        bindService(rfduinoIntent, rfduinoServiceConnection, BIND_AUTO_CREATE);
      }
    });

    // Receive
    clearButton = (Button) findViewById(R.id.clearData);
    clearButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        receivedData.clear();
        receivedDataAdapter.notifyDataSetChanged();
      }
    });
  }

  @Override
  protected void onStart() {
    super.onStart();

    registerReceiver(scanModeReceiver, new IntentFilter(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED));
    registerReceiver(bluetoothStateReceiver,
        new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
    registerReceiver(rfduinoReceiver, RFduinoService.getIntentFilter());

    updateState(bluetoothAdapter.isEnabled() ? STATE_DISCONNECTED : STATE_BLUETOOTH_OFF);
  }

  @Override
  protected void onStop() {
    super.onStop();

    scanForRFduinoDevice(false);

    unregisterReceiver(scanModeReceiver);
    unregisterReceiver(bluetoothStateReceiver);
    unregisterReceiver(rfduinoReceiver);
  }

  //////////////////
  // Options menu //
  //////////////////

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.menu_main, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    switch (item.getItemId()) {
      case R.id.action_enable_bluetooth:
        ensureBluetoothEnabled();
        return true;
      case R.id.action_export_log:
        exportLog();
        return true;
      case R.id.action_remove_log:
        removeLog();
        return true;
      default:
        return super.onOptionsItemSelected(item);
    }
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    super.onPrepareOptionsMenu(menu);
    Log.d(TAG, String.format("state: %d", state));
    menu.findItem(R.id.action_enable_bluetooth).setEnabled(state == STATE_BLUETOOTH_OFF);
    return true;
  }

  ////////////////////
  // Helper methods //
  ////////////////////

  private void scanForRFduinoDevice(final boolean enableScan) {
    if (enableScan) {
      // set up to stop scan after MAX_SCAN_PERIOD (scanning *destroys* battery life)
      handler.postDelayed(new Runnable() {
        @Override
        public void run() {
          scanning = false;
          bluetoothAdapter.stopLeScan(leScanCallback);
        }
      }, MAX_SCAN_PERIOD);
      // start scan
      scanning = true;
      bluetoothAdapter.startLeScan(new UUID[] { RFduinoService.UUID_SERVICE }, leScanCallback);
    } else {
      scanning = false;
      bluetoothAdapter.stopLeScan(leScanCallback);
    }
  }

  private BluetoothAdapter.LeScanCallback leScanCallback = new BluetoothAdapter.LeScanCallback() {
    @Override
    public void onLeScan(final BluetoothDevice device, final int rssi, final byte[] scanRecord) {
      bluetoothDevice = device;
      upgradeState(STATE_DISCONNECTED);
      updateUi();

      // for now, we'll shortcut the scan as soon as we've found the first RFDuino device
      scanForRFduinoDevice(false);

      runOnUiThread(new Runnable() {
        @Override public void run() {
          deviceInfoText.setText(
              BluetoothHelper.getDeviceInfoText(bluetoothDevice, rssi, scanRecord));
        }
      });
    }
  };

  private void ensureBluetoothEnabled() {
    final BluetoothManager bluetoothManager =
        (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
    bluetoothAdapter = bluetoothManager.getAdapter();
    if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
      Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
      startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
    }
  }

  private void upgradeState(int newState) {
    if (newState > state) {
      updateState(newState);
    }
  }

  private void downgradeState(int newState) {
    if (newState < state) {
      updateState(newState);
    }
  }

  private void updateState(int newState) {
    state = newState;
    updateUi();
  }

  private void updateUi() {

    Log.d(TAG, "updateUI");

    if (state > STATE_BLUETOOTH_OFF) {
      scanButton.setEnabled(true);
    }

    if (scanning) {
      scanStatusText.setText("Scanning...");
      scanButton.setText("Stop Scan");
    } else {
      scanStatusText.setText("");
      scanButton.setTag("Scan");
    }
    scanButton.setEnabled(true);

    if (bluetoothDevice != null && state == STATE_DISCONNECTED) {
      connectionStatusText.setText("Disconnected");
      connectButton.setEnabled(true);
    } else if (state == STATE_CONNECTING) {
      connectionStatusText.setText("Connecting...");
    } else if (state == STATE_CONNECTED) {
      connectionStatusText.setText("Connected");
    }
  }

  private void addData(byte[] data) {
    // The test RFDuino just sends a floating point value every second
    // This value represents the temperature of the device in celsius
    // https://github.com/RFduino/RFduino/blob/master/libraries/RFduinoBLE/examples/Temperature/Temperature.ino
    // Arduino floats stored as 4 bytes (max of 20 bytes in a message for BTLE)
    // http://arduino.cc/en/Reference/Float

    Log.d(TAG, "hello addData");

    // receive (and normalize if necessary) datum from the RFDuino
    int receivedValue = (int) ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getFloat();
    Log.d(TAG, String.format("raw datum received: %d", receivedValue));

    // display datum on slider
    slider.setProgress(receivedValue);

    // push datum to log file (timestamp, value)
    log(String.valueOf(receivedValue));
  }

  public void log(String message) {
    try {
      FileOutputStream out = openFileOutput(LOG_FNAME, MODE_APPEND);
      out.write(String.format("%s,%s\n", Utility.getDateTimeString(), message).getBytes());
      out.close();
    } catch (IOException e) {
      Log.e(TAG, e.toString());
    }
  }

  public void exportLog() {
    File logFile = new File(getFilesDir() + "/" + LOG_FNAME);
    Uri attachmentUri =
        FileProvider.getUriForFile(this, "net.philadams.keppi.fileprovider", logFile);
    Intent emailIntent = new Intent(Intent.ACTION_SEND);
    emailIntent.putExtra(Intent.EXTRA_EMAIL, EMAIL_RECIPIENTS);
    emailIntent.putExtra(Intent.EXTRA_SUBJECT, "Keppi log file");
    emailIntent.putExtra(Intent.EXTRA_TEXT, "Log file (timestamp,datum) attached.");
    emailIntent.setType("plain/text");
    emailIntent.putExtra(Intent.EXTRA_STREAM, attachmentUri);
    emailIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    startActivity(emailIntent);
  }

  public void removeLog() {
    File logFile = new File(getFilesDir() + "/" + LOG_FNAME);
    logFile.delete();
  }
}