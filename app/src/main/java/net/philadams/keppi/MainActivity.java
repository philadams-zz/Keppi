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
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.UUID;

public class MainActivity extends Activity {

  public static final String TAG = MainActivity.class.getSimpleName();

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

  private TextView scanStatusText;
  private Button scanButton;
  private TextView deviceInfoText;
  private TextView connectionStatusText;
  private Button connectButton;
  private Button clearButton;
  private LinearLayout dataLayout;

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

    // ensure bluetooth on (with user permission)
    ensureBluetoothEnabled();

    // scanning for RFDuino
    scanStatusText = (TextView) findViewById(R.id.scanStatus);
    scanButton = (Button) findViewById(R.id.scan);
    scanButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        ensureBluetoothEnabled();
        scanForRFduinoDevice(true);
      }
    });

    // device info
    deviceInfoText = (TextView) findViewById(R.id.deviceInfo);

    // connecting to the device
    connectionStatusText = (TextView) findViewById(R.id.connectionStatus);
    connectButton = (Button) findViewById(R.id.connect);
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
        dataLayout.removeAllViews();
      }
    });

    dataLayout = (LinearLayout) findViewById(R.id.dataLayout);
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
    View view = getLayoutInflater().inflate(android.R.layout.simple_list_item_2, dataLayout, false);

    TextView text1 = (TextView) view.findViewById(android.R.id.text1);
    text1.setText(HexAsciiHelper.bytesToHex(data));

    String ascii = HexAsciiHelper.bytesToAsciiMaybe(data);
    if (ascii != null) {
      TextView text2 = (TextView) view.findViewById(android.R.id.text2);
      text2.setText(ascii);
    }

    dataLayout.addView(view, LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT);
  }
}

