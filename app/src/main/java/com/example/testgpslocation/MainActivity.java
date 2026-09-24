package com.example.testgpslocation;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.testgpslocation.service.LocationTrackerService;
import com.example.testgpslocation.ui.LocationViewModel;
import com.example.testgpslocation.utils.TelemetryLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 3001;
    private LocationViewModel viewModel;
    
    private TextView statusIndicator, locationLabel, locationText, tvLogFilePath;
    private TextView speedVal, headingVal, distanceVal;
    private TextView currentStationText, nextStationText, distToNextText;
    private Button btnStartLogging, btnStopLogging;
    private HistoryAdapter historyAdapter;
    private RecyclerView historyList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        
        viewModel = new ViewModelProvider(this).get(LocationViewModel.class);

        observeViewModel();
        requestRequiredPermissions();
    }

    private void initViews() {
        statusIndicator = findViewById(R.id.statusIndicator);
        locationLabel = findViewById(R.id.locationLabel);
        locationText = findViewById(R.id.locationText);
        
        speedVal = findViewById(R.id.speedVal);
        headingVal = findViewById(R.id.headingVal);
        distanceVal = findViewById(R.id.distanceVal);

        currentStationText = findViewById(R.id.currentStationText);
        nextStationText = findViewById(R.id.nextStationText);
        distToNextText = findViewById(R.id.distToNextText);

        tvLogFilePath = findViewById(R.id.tvLogFilePath);
        btnStartLogging = findViewById(R.id.btnStartLogging);
        btnStopLogging = findViewById(R.id.btnStopLogging);
        
        historyList = findViewById(R.id.historyList);
        historyList.setLayoutManager(new LinearLayoutManager(this));
        historyAdapter = new HistoryAdapter();
        historyList.setAdapter(historyAdapter);

        tvLogFilePath.setText("Path: " + TelemetryLogger.getLogFile(this).getAbsolutePath());

        btnStartLogging.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, LocationTrackerService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(MainActivity.this, intent);
            } else {
                startService(intent);
            }
            Toast.makeText(this, "Telemetry Logging Started", Toast.LENGTH_SHORT).show();
        });

        btnStopLogging.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, LocationTrackerService.class);
            stopService(intent);
            Toast.makeText(this, "Telemetry Logging Stopped", Toast.LENGTH_SHORT).show();
        });
    }

    private void observeViewModel() {
        viewModel.getCurrentLocation().observe(this, loc -> {
            if (loc != null) {
                boolean active = loc.isActive();
                if (active) {
                    locationText.setVisibility(View.VISIBLE);
                    historyList.setVisibility(View.GONE);
                    locationText.setText(String.format(Locale.US, "%.6f, %.6f", loc.getLatitude(), loc.getLongitude()));
                } else {
                    locationText.setVisibility(View.GONE);
                    historyList.setVisibility(View.VISIBLE);
                }
                statusIndicator.setText(loc.isActive() ? "GPS ACTIVE" : "GPS DISCONNECTED");
                statusIndicator.setTextColor(loc.isActive() ? Color.parseColor("#4CAF50") : Color.parseColor("#F44336"));
            }
        });

        viewModel.getLiveStats().observe(this, stats -> {
            locationLabel.setText(stats.getLabel());
            speedVal.setText(String.format(Locale.US, "%.1f", stats.getSpeed()));
            headingVal.setText(String.format(Locale.US, "%.0f°", stats.getHeading()));
            distanceVal.setText(String.format(Locale.US, "%.0fm", stats.getDistance()));

            // Update Metro Navigation Details with scanning feedback
            currentStationText.setText(stats.getCurrentStation());
            nextStationText.setText(stats.getNextStation());
            
            TextView destinationText = findViewById(R.id.destinationText);
            
            if (stats.getNavActive()) {
                distToNextText.setText(String.format(Locale.US, "Distance to next stop: %.0fm", stats.getDistToNext()));
                destinationText.setText("Heading To: " + stats.getDestination());
                currentStationText.setTextColor(Color.parseColor("#FFFFFF"));
                nextStationText.setTextColor(Color.parseColor("#03A9F4"));
            } else {
                distToNextText.setText(stats.getLineName() != null ? "Approaching " + stats.getLineName() : "Searching for metro line...");
                destinationText.setText("Heading To: --");
                currentStationText.setTextColor(Color.parseColor("#757575"));
                nextStationText.setTextColor(Color.parseColor("#757575"));
            }
        });

        viewModel.getMovementHistory().observe(this, history -> {
            historyAdapter.setItems(history);
            if (historyList.getVisibility() == View.VISIBLE && !history.isEmpty()) {
                historyList.scrollToPosition(0);
            }
        });
    }

    private void requestRequiredPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        perms.add(Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        String[] permissions = perms.toArray(new String[0]);
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            viewModel.startTracking();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        viewModel.stopTracking();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            viewModel.startTracking();
        }
    }

    private static class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.ViewHolder> {
        private List<String> items = new ArrayList<>();
        void setItems(List<String> newItems) { this.items = newItems; notifyDataSetChanged(); }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_1, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.textView.setText(items.get(position));
            holder.textView.setTextColor(Color.parseColor("#00E676"));
            holder.textView.setTextSize(14);
            holder.textView.setPadding(32, 12, 32, 12);
        }

        @Override
        public int getItemCount() { return items.size(); }
        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView textView;
            ViewHolder(View v) { super(v); textView = v.findViewById(android.R.id.text1); }
        }
    }
}
