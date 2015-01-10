package net.philadams.keppi;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;

public class MainActivity extends ActionBarActivity {

  private static final String TAG = MainActivity.class.getSimpleName();
  private static final int REQUEST_ENABLE_BT = 0;
  private static final int SCAN_PERIOD = 5000;

  private BluetoothAdapter bluetoothAdapter;
  private boolean isScanning;
  private Handler handler;
  private DeviceListAdapter deviceListAdapter;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    handler = new Handler();
  }

  @Override
  protected void onResume() {
    super.onResume();

    ensureBluetoothIsEnabled();

    deviceListAdapter = new DeviceListAdapter();
    ListView devicesListView = (ListView) findViewById(R.id.devices_list);
    devicesListView.setAdapter(deviceListAdapter);

    scanForDevices(true);
  }

  @Override
  protected void onPause() {
    super.onPause();
    scanForDevices(false);
    deviceListAdapter.clear();
  }

  private void ensureBluetoothIsEnabled() {
    final BluetoothManager bluetoothManager =
        (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
    bluetoothAdapter = bluetoothManager.getAdapter();
    if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
      Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
      startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
    }
  }

  private void scanForDevices(boolean enable) {
    if (enable) {

      // stops scanning after a a pre-defined scan period
      handler.postDelayed(new Runnable() {
        @Override
        public void run() {
          isScanning = false;
          bluetoothAdapter.stopLeScan(scanCallback);
        }
      }, SCAN_PERIOD);

      isScanning = true;
      bluetoothAdapter.startLeScan(scanCallback);

    } else {
      isScanning = false;
      bluetoothAdapter.stopLeScan(scanCallback);
    }
    invalidateOptionsMenu();
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    // Inflate the menu; this adds items to the action bar if it is present.
    getMenuInflater().inflate(R.menu.menu_main, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    // Handle action bar item clicks here. The action bar will
    // automatically handle clicks on the Home/Up button, so long
    // as you specify a parent activity in AndroidManifest.xml.
    int id = item.getItemId();

    //noinspection SimplifiableIfStatement
    if (id == R.id.action_settings) {
      return true;
    }

    return super.onOptionsItemSelected(item);
  }

  public class DeviceListAdapter extends BaseAdapter {

    private ArrayList<BluetoothDevice> devices;
    private LayoutInflater layoutInflater;

    public DeviceListAdapter() {
      super();
      devices = new ArrayList<BluetoothDevice>();
      layoutInflater = MainActivity.this.getLayoutInflater();
    }

    public void addDevice(BluetoothDevice device) {
      if (!devices.contains(device)) {
        devices.add(device);
      }
    }

    public BluetoothDevice getDevice(int position) {
      return devices.get(position);
    }

    public void clear() {
      devices.clear();
    }

    @Override
    public int getCount() {
      return devices.size();
    }

    @Override
    public Object getItem(int i) {
      return devices.get(i);
    }

    @Override
    public long getItemId(int i) {
      return i;
    }

    @Override
    public View getView(int i, View view, ViewGroup viewGroup) {
      ViewHolder viewHolder;

      if (view == null) {
        view = layoutInflater.inflate(android.R.layout.simple_list_item_2, null);
        viewHolder = new ViewHolder();
        viewHolder.deviceName = (TextView) view.findViewById(android.R.id.text2);
        viewHolder.deviceAddress = (TextView) view.findViewById(android.R.id.text1);
        view.setTag(viewHolder);
      } else {
        viewHolder = (ViewHolder) view.getTag();
      }

      BluetoothDevice device = devices.get(i);
      final String deviceName = device.getName();
      if (deviceName != null && deviceName.length() > 0) {
        viewHolder.deviceName.setText(deviceName);
      } else {
        viewHolder.deviceName.setText(android.R.string.unknownName);
      }
      viewHolder.deviceAddress.setText(device.getAddress());

      return view;
    }
  }


  private BluetoothAdapter.LeScanCallback scanCallback = new BluetoothAdapter.LeScanCallback() {

    @Override
    public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
      runOnUiThread(new Runnable() {
        @Override
        public void run() {
          deviceListAdapter.addDevice(device);
          deviceListAdapter.notifyDataSetChanged();
        }
      });
    }
  };

  static class ViewHolder {
    TextView deviceName;
    TextView deviceAddress;
  }
}
