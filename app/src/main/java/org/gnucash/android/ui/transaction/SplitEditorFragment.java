/*
 * Copyright (c) 2014 - 2015 Ngewi Fet <ngewif@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gnucash.android.ui.transaction;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.widget.SimpleCursorAdapter;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import org.gnucash.android.R;
import org.gnucash.android.db.AccountsDbAdapter;
import org.gnucash.android.db.DatabaseSchema;
import org.gnucash.android.model.AccountType;
import org.gnucash.android.model.Money;
import org.gnucash.android.model.Split;
import org.gnucash.android.model.Transaction;
import org.gnucash.android.model.TransactionType;
import org.gnucash.android.ui.FormActivity;
import org.gnucash.android.ui.UxArgument;
import org.gnucash.android.ui.transaction.dialog.TransferFundsDialogFragment;
import org.gnucash.android.ui.util.AmountInputFormatter;
import org.gnucash.android.ui.util.OnTransferFundsListener;
import org.gnucash.android.ui.util.TransactionTypeSwitch;
import org.gnucash.android.util.QualifiedAccountNameCursorAdapter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import butterknife.Bind;
import butterknife.ButterKnife;

/**
 * Dialog for editing the splits in a transaction
 *
 * @author Ngewi Fet <ngewif@gmail.com>
 */
public class SplitEditorFragment extends Fragment {

    @Bind(R.id.split_list_layout) LinearLayout mSplitsLinearLayout;
    @Bind(R.id.imbalance_textview) TextView mImbalanceTextView;
    @Bind(R.id.btn_add_split) Button mAddSplit;

    private AccountsDbAdapter mAccountsDbAdapter;
    private Cursor mCursor;
    private SimpleCursorAdapter mCursorAdapter;
    private List<View> mSplitItemViewList;
    private String mAccountUID;

    private BalanceTextWatcher mBalanceUpdater = new BalanceTextWatcher();
    private BigDecimal mBaseAmount = BigDecimal.ZERO;

    private ArrayList<String> mRemovedSplitUIDs = new ArrayList<>();

    /**
     * Create and return a new instance of the fragment with the appropriate paramenters
     * @param args Arguments to be set to the fragment. <br>
     *             See {@link UxArgument#AMOUNT_STRING} and {@link UxArgument#SPLIT_LIST}
     * @return New instance of SplitEditorFragment
     */
    public static SplitEditorFragment newInstance(Bundle args){
        SplitEditorFragment fragment = new SplitEditorFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_split_editor, container, false);
        ButterKnife.bind(this, view);

        mAddSplit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                addSplitView(null);
            }
        });
        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        ActionBar actionBar = ((AppCompatActivity)getActivity()).getSupportActionBar();
        assert actionBar != null;
        actionBar.setTitle(R.string.title_transaction_splits);
        setHasOptionsMenu(true);

        mSplitItemViewList = new ArrayList<>();

        //we are editing splits for a new transaction.
        // But the user may have already created some splits before. Let's check
        List<String> splitStrings = getArguments().getStringArrayList(UxArgument.SPLIT_LIST);
        List<Split> splitList = new ArrayList<>();
        if (splitStrings != null) {
            for (String splitString : splitStrings) {
                splitList.add(Split.parseSplit(splitString));
            }
        }

        initArgs();
        if (!splitList.isEmpty()) {
            //aha! there are some splits. Let's load those instead
            loadSplitViews(splitList);
        } else {
            final Currency currency = Currency.getInstance(mAccountsDbAdapter.getAccountCurrencyCode(mAccountUID));
            Split split = new Split(new Money(mBaseAmount.abs(), currency), mAccountUID);
            AccountType accountType = mAccountsDbAdapter.getAccountType(mAccountUID);
            TransactionType transactionType = Transaction.getTypeForBalance(accountType, mBaseAmount.signum() < 0);
            split.setType(transactionType);
            View view = addSplitView(split);
            view.findViewById(R.id.input_accounts_spinner).setEnabled(false);
            view.findViewById(R.id.btn_remove_split).setVisibility(View.GONE);
        }

        updateTotal();
    }

    private void loadSplitViews(List<Split> splitList) {
        for (Split split : splitList) {
            addSplitView(split);
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.default_save_actions, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getItemId()) {
            case android.R.id.home:
                getActivity().setResult(Activity.RESULT_CANCELED);
                getActivity().finish();
                return true;

            case R.id.menu_save:
                saveSplits();
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    /**
     * Add a split view and initialize it with <code>split</code>
     * @param split Split to initialize the contents to
     * @return Returns the split view which was added
     */
    private View addSplitView(Split split){
        LayoutInflater layoutInflater = getActivity().getLayoutInflater();
        View splitView = layoutInflater.inflate(R.layout.item_split_entry, mSplitsLinearLayout, false);
        mSplitsLinearLayout.addView(splitView,0);
        SplitViewHolder viewHolder = new SplitViewHolder(splitView, split);
        splitView.setTag(viewHolder);
        mSplitItemViewList.add(splitView);
        return splitView;
    }

    /**
     * Extracts arguments passed to the view and initializes necessary adapters and cursors
     */
    private void initArgs() {
        mAccountsDbAdapter = AccountsDbAdapter.getInstance();

        Bundle args = getArguments();
        mAccountUID = ((FormActivity) getActivity()).getCurrentAccountUID();
        mBaseAmount = new BigDecimal(args.getString(UxArgument.AMOUNT_STRING));

        String conditions = "("
                + DatabaseSchema.AccountEntry.COLUMN_HIDDEN + " = 0 AND "
                + DatabaseSchema.AccountEntry.COLUMN_PLACEHOLDER + " = 0"
                + ")";
        mCursor = mAccountsDbAdapter.fetchAccountsOrderedByFullName(conditions, null);
    }

    /**
     * Holds a split item view and binds the items in it
     */
    class SplitViewHolder implements OnTransferFundsListener{
        @Bind(R.id.input_split_memo)        EditText splitMemoEditText;
        @Bind(R.id.input_split_amount)      EditText splitAmountEditText;
        @Bind(R.id.btn_remove_split)        ImageView removeSplitButton;
        @Bind(R.id.input_accounts_spinner)  Spinner accountsSpinner;
        @Bind(R.id.split_currency_symbol)   TextView splitCurrencyTextView;
        @Bind(R.id.split_uid)               TextView splitUidTextView;
        @Bind(R.id.btn_split_type)          TransactionTypeSwitch splitTypeButton;

        View splitView;
        Money quantity;
        AmountInputFormatter amountInputFormatter;

        public SplitViewHolder(View splitView, Split split){
            ButterKnife.bind(this, splitView);
            this.splitView = splitView;
            if (split != null)
                this.quantity = split.getQuantity();
            setListeners(split);
        }

        @Override
        public void transferComplete(Money amount) {
            quantity = amount;
        }

        private void setListeners(Split split){
            amountInputFormatter = new AmountInputFormatter(splitAmountEditText);
            splitAmountEditText.addTextChangedListener(amountInputFormatter);

            removeSplitButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    mRemovedSplitUIDs.add(splitUidTextView.getText().toString());
                    mSplitsLinearLayout.removeView(splitView);
                    mSplitItemViewList.remove(splitView);
                    updateTotal();
                }
            });

            updateTransferAccountsList(accountsSpinner);

            Currency accountCurrency = Currency.getInstance(mAccountsDbAdapter.getCurrencyCode(mAccountUID));
            splitCurrencyTextView.setText(accountCurrency.getSymbol());
            splitTypeButton.setAmountFormattingListener(splitAmountEditText, splitCurrencyTextView);
            splitTypeButton.setChecked(mBaseAmount.signum() > 0);
            splitUidTextView.setText(UUID.randomUUID().toString());

            if (split != null) {
                splitAmountEditText.setText(split.getFormattedValue().toPlainString());
                splitCurrencyTextView.setText(split.getValue().getCurrency().getSymbol());
                splitMemoEditText.setText(split.getMemo());
                splitUidTextView.setText(split.getUID());
                String splitAccountUID = split.getAccountUID();
                setSelectedTransferAccount(mAccountsDbAdapter.getID(splitAccountUID), accountsSpinner);
                splitTypeButton.setAccountType(mAccountsDbAdapter.getAccountType(splitAccountUID));
                splitTypeButton.setChecked(split.getType());
            }

            accountsSpinner.setOnItemSelectedListener(new SplitAccountListener(splitTypeButton, this));

            //put these balance update triggers last last so as to avoid computing while still loading
            splitAmountEditText.addTextChangedListener(mBalanceUpdater);
            splitTypeButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    updateTotal();
                }
            });
        }
    }

    /**
     * Updates the spinner to the selected transfer account
     * @param accountId Database ID of the transfer account
     */
    private void setSelectedTransferAccount(long accountId, final Spinner accountsSpinner){
        for (int pos = 0; pos < mCursorAdapter.getCount(); pos++) {
            if (mCursorAdapter.getItemId(pos) == accountId){
                accountsSpinner.setSelection(pos);
                break;
            }
        }
    }
    /**
     * Updates the list of possible transfer accounts.
     * Only accounts with the same currency can be transferred to
     */
    private void updateTransferAccountsList(Spinner transferAccountSpinner){
        mCursorAdapter = new QualifiedAccountNameCursorAdapter(getActivity(), mCursor);
        transferAccountSpinner.setAdapter(mCursorAdapter);
    }

    private void saveSplits() {
        List<Split> splitList = extractSplitsFromView();
        ArrayList<String> splitStrings = new ArrayList<>();
        for (Split split : splitList) {
            splitStrings.add(split.toCsv());
        }
        Intent data = new Intent();
        data.putStringArrayListExtra(UxArgument.SPLIT_LIST, splitStrings);
        data.putStringArrayListExtra(UxArgument.REMOVED_SPLITS, mRemovedSplitUIDs);
        getActivity().setResult(Activity.RESULT_OK, data);

        getActivity().finish();
    }

    /**
     * Extracts the input from the views and builds {@link org.gnucash.android.model.Split}s to correspond to the input.
     * @return List of {@link org.gnucash.android.model.Split}s represented in the view
     */
    private List<Split> extractSplitsFromView(){
        List<Split> splitList = new ArrayList<>();
        for (View splitView : mSplitItemViewList) {
            SplitViewHolder viewHolder = (SplitViewHolder) splitView.getTag();
            if (viewHolder.splitAmountEditText.getText().toString().isEmpty())
                continue;

            BigDecimal amountBigDecimal = TransactionFormFragment.parseInputToDecimal(viewHolder.splitAmountEditText.getText().toString());
            String currencyCode = mAccountsDbAdapter.getCurrencyCode(mAccountUID);
            Money valueAmount = new Money(amountBigDecimal, Currency.getInstance(currencyCode));

            String accountUID = mAccountsDbAdapter.getUID(viewHolder.accountsSpinner.getSelectedItemId());
            Split split = new Split(valueAmount, accountUID);
            split.setMemo(viewHolder.splitMemoEditText.getText().toString());
            split.setType(viewHolder.splitTypeButton.getTransactionType());
            split.setUID(viewHolder.splitUidTextView.getText().toString().trim());
            if (viewHolder.quantity != null)
                split.setQuantity(viewHolder.quantity);
            splitList.add(split);
        }
        return splitList;
    }

    /**
     * Updates the displayed total for the transaction.
     * Computes the total of the splits, the unassigned balance and the split sum
     */
    private void updateTotal(){
        List<Split> splitList   = extractSplitsFromView();
        String currencyCode     = mAccountsDbAdapter.getCurrencyCode(mAccountUID);
        Money splitSum          = Money.createZeroInstance(currencyCode);
        for (Split split : splitList) {
            Money amount = split.getValue().absolute();
            if (split.getType() == TransactionType.DEBIT)
                splitSum = splitSum.subtract(amount);
            else
                splitSum = splitSum.add(amount);
        }
        TransactionsActivity.displayBalance(mImbalanceTextView, splitSum);
    }

    /**
     * Updates the displayed balance of the accounts when the amount of a split is changed
     */
    private class BalanceTextWatcher implements TextWatcher {

        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i2, int i3) {

        }

        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i2, int i3) {

        }

        @Override
        public void afterTextChanged(Editable editable) {
            updateTotal();
        }
    }

    /**
     * Listens to changes in the transfer account and updates the currency symbol, the label of the
     * transaction type and if neccessary
     */
    private class SplitAccountListener implements AdapterView.OnItemSelectedListener {
        TransactionTypeSwitch mTypeToggleButton;
        SplitViewHolder mSplitViewHolder;

        /**
         * Flag to know when account spinner callback is due to user interaction or layout of components
         */
        boolean userInteraction = false;

        public SplitAccountListener(TransactionTypeSwitch typeToggleButton, SplitViewHolder viewHolder){
            this.mTypeToggleButton = typeToggleButton;
            this.mSplitViewHolder = viewHolder;
        }

        @Override
        public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
            AccountType accountType = mAccountsDbAdapter.getAccountType(id);
            mTypeToggleButton.setAccountType(accountType);

            String fromCurrencyCode = mAccountsDbAdapter.getCurrencyCode(mAccountUID);
            String targetCurrencyCode = mAccountsDbAdapter.getCurrencyCode(mAccountsDbAdapter.getUID(id));

            if (!userInteraction || fromCurrencyCode.equals(targetCurrencyCode)){
                //first call is on layout, subsequent calls will be true and transfer will work as usual
                userInteraction = true;
                return;
            }

            String stringAmount = mSplitViewHolder.splitAmountEditText.getText().toString();
            if (stringAmount.isEmpty())
                return;

            Money amount = new Money(
                    TransactionFormFragment.parseInputToDecimal(stringAmount),
                    Currency.getInstance(fromCurrencyCode));
            TransferFundsDialogFragment fragment
                    = TransferFundsDialogFragment.getInstance(amount, targetCurrencyCode, mSplitViewHolder);
            fragment.show(getFragmentManager(), "tranfer_funds_editor");
        }

        @Override
        public void onNothingSelected(AdapterView<?> adapterView) {
            //nothing to see here, move along
        }
    }

}