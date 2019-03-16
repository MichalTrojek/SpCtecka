package com.example.android.skladovypomocnik;

import android.Manifest;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;
import com.google.gson.Gson;
import com.jakewharton.processphoenix.ProcessPhoenix;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    private ListView list;
    private ArrayList<Article> articles;
    private ArticleListAdapter listViewAdapter;
    private boolean containsEan = false;
    private AlertDialog deleteDialog;
    private AlertDialog ipDialog;
    private int selectedIndex;
    private Settings settings;
    private EditText inputEanText;
    private EditText inputIpAddress;
    private EditText inputAmount;
    private EditText inputFilename;
    private TextView ipAddressTextView;
    private TextView loadingInfoTextView;
    private TextView bookNameTextView;
    private TextView amountTextView;
    private ProgressBar progress;
    private AlertDialog loadingDialog;
    private String TAG = "MainActivity";


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        MobileAds.initialize(this, "ca-app-pub-6403268384265634~1982638427");
        settings = new Settings(this);
        askForPermission();
        inputEanText = (EditText) findViewById(R.id.inputEanText);
        inputEanText.requestFocus();

        inputFilename = (EditText) findViewById(R.id.filenameEditText);

        articles = new ArrayList<>();
        listViewAdapter = new ArticleListAdapter(this, R.layout.adapter_layout, articles);


        list = (ListView) findViewById(R.id.listView);
        list.setAdapter(listViewAdapter);
        list.setFocusable(false);

        handleAds();


        createIpAddressAlertDialog();
        createListItemAlertDialog();
        setButtonListeners();
        setInputListener();
        setSelectedItemListener();


    }

    public void askForPermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                // you already have a permission
                checkForDatabaseUpdate();
            } else {
                // asks for permission
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
                Toast.makeText(MainActivity.this, "Čekejte", Toast.LENGTH_LONG).show();
            }
        } else { //you dont need to worry about these stuff below api level 23

        }
    }

    //  After user allows permissions, it checks if the database is up to date. If not, it offers update.
    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkForDatabaseUpdate();
            } else {
                Toast.makeText(MainActivity.this, "Čekejte", Toast.LENGTH_LONG).show();
                preloadDatabaseData();
            }
        }
    }


    int onlineDbVersionNumber;

    private void checkForDatabaseUpdate() {
        Retrofit retrofit = new Retrofit.Builder().baseUrl(Api.BASE_URL).addConverterFactory(GsonConverterFactory.create()).build();
        Api api = retrofit.create(Api.class);
        Call<DatabaseVersion> call = api.getDatabaseVersionInfo();
        call.enqueue(new Callback<DatabaseVersion>() {
            @Override
            public void onResponse(Call<DatabaseVersion> call, Response<DatabaseVersion> response) {
                if (!response.isSuccessful()) {
                    Toast.makeText(getApplicationContext(), "Dotaz nebyl úspešný" + response.code(), Toast.LENGTH_SHORT).show();
                    return;
                } else {
                    DatabaseVersion version = response.body();
                    onlineDbVersionNumber = Integer.valueOf(version.getDatabaseVersion());
                    if (onlineDbVersionNumber > settings.getCurrentDatabaseVersion()) {
                        updateFoundDialog(MainActivity.this);
                    } else {
                        preloadDatabaseData();
                    }
                }
            }

            @Override
            public void onFailure(Call<DatabaseVersion> call, Throwable t) {
                preloadDatabaseData();
                Toast.makeText(getApplicationContext(), "Nepřipojeno k internetu", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void updateFoundDialog(Context context) {
        new AlertDialog.Builder(context).setTitle("Aktualizace databaze").setMessage("Je dostupna aktualizace database.").setPositiveButton("Aktualizovat", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                updateDatabase();
            }
        }).setNegativeButton("Neaktualizovat", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                Toast.makeText(MainActivity.this, "Čekejte", Toast.LENGTH_LONG).show();
                preloadDatabaseData();
            }
        }).show();
    }

    private void updateDatabase() {
        try {
            downloadDatabaseFromUrl("http://www.skladovypomocnik.cz/BooksDatabase.db");
            Toast.makeText(this, "Stahování zahájeno, čekejte. Aplikace se po stáhnutí restartuje.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void preloadDatabaseData() {
        createLoadingDialog();
        if (Model.getInstance().getNamesAndPrices() == null) {
            DatabaseLoaderAsyncTask databaseLoader = new DatabaseLoaderAsyncTask(this, loadingDialog, progress, loadingInfoTextView);
            databaseLoader.execute();
        }
    }

    private void handleAds() {
        AdView adView = (AdView) findViewById(R.id.adView);
        AdRequest adRequest = new AdRequest.Builder().addTestDevice(AdRequest.DEVICE_ID_EMULATOR).build();
        adView.loadAd(adRequest);
    }

    @Override
    public void onStop() {
        super.onStop();
        settings.setArticles(articles);
    }

    @Override
    public void onStart() {
        super.onStart();
        articles.clear();
        articles.addAll(settings.getArticles());
        listViewAdapter.notifyDataSetChanged();
        refreshAmountTextView();
    }


    private void refreshAmountTextView() {
        TextView totalAmountTextView = (TextView) findViewById(R.id.totalAmountTextView);
        TextView totalEanAmountTextView = (TextView) findViewById(R.id.totalEanAmountTextView);
        totalAmountTextView.setText("Celkové množství:  " + calculateAmount());
        totalEanAmountTextView.setText("Počet titulů: " + articles.size());
    }

    private int calculateAmount() {
        int amount = 0;
        for (Article a : articles) {
            amount += a.getAmount();
        }
        return amount;
    }


    // when enter key is pressed, value in inputEanTextview is added to listView and amount info is refreshed
    private void setInputListener() {
        inputEanText.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent keyevent) {
                return handleEnterKey(keyCode, keyevent);
            }
        });
    }

    private boolean handleEnterKey(int keyCode, KeyEvent keyevent) {
        if ((keyevent.getAction() == KeyEvent.ACTION_DOWN) && (keyCode == KeyEvent.KEYCODE_ENTER)) {
            addEan();
            hideKeyboard(inputEanText);
            refreshAmountTextView();
            return true;
        }
        return false;
    }

    private void addEan() {
        String ean = inputEanText.getText().toString();
        if (!ean.isEmpty()) {
            handleAddingEan(ean);
        }
    }


    private void handleAddingEan(String ean) {
        if (articles.isEmpty()) {
            articles.add(0, new Article(ean, 1, lookUpNameInDatabase(ean), lookUpPriceInDatabase(ean)));
        } else {
            iterateListForMatch(ean);
        }
        listViewAdapter.notifyDataSetChanged();
        inputEanText.setText("");
    }


    private void iterateListForMatch(String ean) {
        searchListForItemByEan(ean);
        addNewItemIfNotFound(ean);
    }

    private void searchListForItemByEan(String ean) {
        for (int i = 0; i < articles.size(); i++) {
            Article a = articles.get(i);
            containsEan = false;
            if (ean.equalsIgnoreCase(a.getEan())) {
                increaseAmountIfFound(a);
                break;
            }
        }
    }

    private void increaseAmountIfFound(Article a) {
        incrementAmount(a);
        containsEan = true;
    }

    private void incrementAmount(Article a) {
        int amount = a.getAmount() + 1;
        String currentEan = a.getEan();
        articles.remove(a);
        articles.add(0, new Article(currentEan, amount, a.getName(), a.getPrice()));
    }

    private void addNewItemIfNotFound(String ean) {
        if (!containsEan) {
            articles.add(0, new Article(ean, 1, lookUpNameInDatabase(ean), lookUpPriceInDatabase(ean)));
        }
    }

    private String lookUpNameInDatabase(String ean) {
        String name = Model.getInstance().getName(ean);
        if (name == null) {
            name = "NÁZEV NENALEZEN";
        }
        return name;
    }

    private String lookUpPriceInDatabase(String ean) {
        String price = Model.getInstance().getPrice(ean);
        if (price == null) {
            price = "***";
        }
        return price;
    }


    // This one is called when user selects item from listView, it calls a new window that offers delete row, edit ammount and add amount functions.
    private void setSelectedItemListener() {
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int position, long id) {
                handleItemSelected(position);
            }
        });
    }

    private void handleItemSelected(int position) {
        selectedIndex = position;
        bookNameTextView.setText(articles.get(selectedIndex).getName());
        amountTextView.setText("Současné množství: " + articles.get(selectedIndex).getAmount());
        deleteDialog.show();
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.cancelButton:
                handleCancelButton();
                break;
            case R.id.editButton:
                handleEditAmountButton();
                refreshAmountTextView();
                break;
            case R.id.deleteButton:
                handleDeleteItemButton();
                refreshAmountTextView();
                break;
            case R.id.editIpButton:
                handleEditIpButton();
                break;
            case R.id.backButton:
                handleBackButton();
                break;
            case R.id.exportButton:
                handleExportButton();
                break;
            case R.id.deleteAllButton:
                handleDeleteAllItemsButton();
                break;
            case R.id.networkSettingsButton:
                handleNetworkSettingsButton();
                break;
            case R.id.addButton:
                handleAddButton();
                refreshAmountTextView();
                break;
            default:
                break;
        }

    }

    private void handleAddButton() {
        hideKeyboard(inputAmount);
        if (!inputAmount.getText().toString().isEmpty()) {
            addAmount();
        }
        deleteDialog.dismiss();
    }

    private void addAmount() {
        BigInteger amount = new BigInteger(inputAmount.getText().toString());
        if (amount.intValue() > 100000 || amount.intValue() < 0) {
            Toast.makeText(this, "Příliš velké nebo malé číslo", Toast.LENGTH_SHORT).show();
        } else {
            inputAmount.setText("");
            String ean = articles.get(selectedIndex).getEan();
            int newAmount = articles.get(selectedIndex).getAmount() + amount.intValue();
            articles.set(selectedIndex, new Article(ean, newAmount, articles.get(selectedIndex).getName(), articles.get(selectedIndex).getPrice()));
            listViewAdapter.notifyDataSetChanged();
        }
    }

    private void handleCancelButton() {
        hideKeyboard(inputAmount);
        deleteDialog.dismiss();
    }

    private void handleEditAmountButton() {
        hideKeyboard(inputAmount);
        if (!inputAmount.getText().toString().isEmpty()) {
            changeAmount();
        }
        deleteDialog.dismiss();
    }

    private void changeAmount() {
        BigInteger amount = new BigInteger(inputAmount.getText().toString());
        if (amount.intValue() > 100000 || amount.intValue() < 0) {
            Toast.makeText(this, "Příliš velké nebo malé číslo", Toast.LENGTH_SHORT).show();
        } else {
            makeChangeAndNotifyAdapter(amount);
        }
    }

    private void makeChangeAndNotifyAdapter(BigInteger amount) {
        inputAmount.setText("");
        String ean = articles.get(selectedIndex).getEan();
        articles.set(selectedIndex, new Article(ean, amount.intValue(), articles.get(selectedIndex).getName(), articles.get(selectedIndex).getPrice()));
        listViewAdapter.notifyDataSetChanged();
    }


    private void handleDeleteItemButton() {
        hideKeyboard(inputAmount);
        articles.remove((articles.get(selectedIndex)));
        listViewAdapter.notifyDataSetChanged();
        deleteDialog.dismiss();
    }

    private void handleEditIpButton() {
        hideKeyboard(inputIpAddress);
        String ip = inputIpAddress.getText().toString().replace(" ", "");
        if (ip.length() != 0) {
            setNewIpAddress(ip);
        } else {
            Toast.makeText(this, "Vlož IP Adresu", Toast.LENGTH_SHORT).show();
        }
    }

    private void hideKeyboard(EditText editText) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(editText.getWindowToken(), 0);
    }


    private void setNewIpAddress(String ip) {
        settings.setIP(ip);
        ipAddressTextView.setText("IP Adresa : " + settings.getIp());
        ipDialog.dismiss();
        Toast.makeText(this, "IP Adresa : " + settings.getIp() + " uložena", Toast.LENGTH_SHORT).show();
    }


    private void handleBackButton() {
        hideKeyboard(inputIpAddress);
        ipDialog.dismiss();
    }


    private void handleExportButton() {
        hideKeyboard(inputEanText);
        if (listViewAdapter.getCount() == 0) {
            createAndDisplayToast("Není vložený žádný ean");
        } else if (settings.getIp().length() == 0) {
            createAndDisplayToast("Není vložená IP adresa");
        } else if (!fileHasName()) {
            createAndDisplayToast("Není vložený název souboru");
        } else {
            String data = convertListToJson();
            sendData(data);
        }
    }


    private String tempPath = "/temp/";
    private String databaseName = "BooksDatabase.db";
    private BroadcastReceiver onDownloadComplete;
    private DownloadManager dm;

    private void downloadDatabaseFromUrl(String databaseUrl) {
        deleteDatabaseIfExists();
        //setups request
        dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(databaseUrl)).setDescription("Stahování databáze").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE).setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI).setDestinationInExternalPublicDir(tempPath, databaseName);


        final long enqueueIdReference = dm.enqueue(request); // starts downloading by putting request into queue, returns unique long ID


        // listens for download result.
        onDownloadComplete = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (enqueueIdReference == id) {
                    //Downloading finished
                    String sdCard = Environment.getExternalStorageDirectory().toString();
                    File sourceFile = new File(sdCard + "/temp/BooksDatabase.db");
                    File destinationFile = new File("/data/data/com.mtr.spmobile/databases/BooksDatabase.db");
                    if (!destinationFile.exists()) {
                        File destinationFolder = new File("/data/data/com.mtr.spmobile/databases");
                        destinationFolder.mkdirs();
                    }
                    try {
                        copyFileFromExternalToInternalMemory(sourceFile, destinationFile);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        };


        registerReceiver(onDownloadComplete, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }

    public void copyFileFromExternalToInternalMemory(File src, File dst) throws IOException {
        FileChannel inChannel = new FileInputStream(src).getChannel();
        FileChannel outChannel = new FileOutputStream(dst).getChannel();
        try {
            inChannel.transferTo(0, inChannel.size(), outChannel);
        } finally {
            if (inChannel != null)
                inChannel.close();
            if (outChannel != null)
                outChannel.close();
        }
        restartApplication();
    }

    private void restartApplication() {
        settings.setCurrentDatabaseVersion(onlineDbVersionNumber);
        ProcessPhoenix.triggerRebirth(MainActivity.this);
    }


    private void deleteDatabaseIfExists() {
        File file = new File("/sdcard" + tempPath + databaseName);
        if (file.exists()) {
            file.delete();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(onDownloadComplete);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private String convertListToJson() {
        Gson gson = new Gson();
        String fileName = inputFilename.getText().toString();
        Map<String, ArrayList<Article>> nameAndList = new HashMap<>();
        nameAndList.put(fileName, articles);
        String json = gson.toJson(nameAndList);
        return json;
    }


    private void createAndDisplayToast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }


    private boolean fileHasName() {
        return inputFilename.getText().length() > 0;
    }


    private void sendData(String data) {
        Client sender = new Client(settings.getIp(), this);
        sender.execute(data.toString());
    }


    private void handleDeleteAllItemsButton() {
        hideKeyboard(inputEanText);
        if (articles.isEmpty()) {
            Toast.makeText(this, "List je prázdný", Toast.LENGTH_SHORT).show();
        } else {
            showDeleteAllAlertDialog();
        }
    }

    private void showDeleteAllAlertDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        builder.setMessage("Opravdu chcete smazat všechny položky?").setNegativeButton("Ne", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {

            }
        }).setPositiveButton("Ano", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                deleteAll();
            }
        }).create();
        builder.show();

    }

    private void deleteAll() {
        articles.clear();
        listViewAdapter.notifyDataSetChanged();
        inputFilename.setText("");
        refreshAmountTextView();
    }

    private void handleNetworkSettingsButton() {
        ipDialog.show();
    }

    // This creates a dialog window  that shows up after clicking on item in listView
    private void createListItemAlertDialog() {
        View view = getLayoutInflater().inflate(R.layout.edit_listview_dialog, null);
        view.findViewById(R.id.deleteButton).setOnClickListener(MainActivity.this);
        view.findViewById(R.id.cancelButton).setOnClickListener(MainActivity.this);
        view.findViewById(R.id.editButton).setOnClickListener(MainActivity.this);
        view.findViewById(R.id.addButton).setOnClickListener(MainActivity.this);
        amountTextView = (TextView) view.findViewById(R.id.currentAmountTextView);
        bookNameTextView = (TextView) view.findViewById(R.id.bookNameTextView);
        inputAmount = (EditText) view.findViewById(R.id.amountInput);
        deleteDialog = new AlertDialog.Builder(this).setView(view).create();
    }

    // This creates a dialog window that shows up after pressing network settings button
    private void createIpAddressAlertDialog() {
        View view = getLayoutInflater().inflate(R.layout.edit_ip_dialog, null);
        view.findViewById(R.id.editIpButton).setOnClickListener(MainActivity.this);
        view.findViewById(R.id.backButton).setOnClickListener(MainActivity.this);
        inputIpAddress = (EditText) view.findViewById(R.id.editIpText);
        ipAddressTextView = (TextView) view.findViewById(R.id.ipAddressTextView);
        ipAddressTextView.setText("IP Adresa : " + settings.getIp());
        ipDialog = new AlertDialog.Builder(this).setView(view).create();
    }

    // This create a dialog windows that shows up after application is started for first time
    private void createLoadingDialog() {
        View view = getLayoutInflater().inflate(R.layout.start_loading, null);
        progress = view.findViewById(R.id.progressBar);
        loadingInfoTextView = (TextView) view.findViewById(R.id.loadingInfoTextView);
        loadingDialog = new AlertDialog.Builder(this).setView(view).create();
    }

    private void setButtonListeners() {
        this.findViewById(R.id.exportButton).setOnClickListener(this);
        this.findViewById(R.id.deleteAllButton).setOnClickListener(this);
        this.findViewById(R.id.networkSettingsButton).setOnClickListener(this);
    }

}
