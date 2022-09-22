package com.sap.cap.bookstore.handlers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;


import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.ql.cqn.CqnSelect;
import com.sap.cds.ql.cqn.CqnUpdate;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.cds.CdsService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.After;
import com.sap.cds.services.handler.annotations.Before;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.persistence.PersistenceService;


import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import cds.gen.booksservice.Books;
import cds.gen.booksservice.Books_;
import cds.gen.ordersservice.OrderItems;
import cds.gen.ordersservice.OrderItems_;
import cds.gen.ordersservice.Orders;
import cds.gen.ordersservice.OrdersService_;
import cds.gen.ordersservice.Orders_;
import lombok.RequiredArgsConstructor;

@Service
@ServiceName(OrdersService_.CDS_NAME)
@RequiredArgsConstructor
public class OrdersService implements EventHandler
{
    private final PersistenceService  dbSrv;  

    /*
     --- Validate Books and decrease Stock for Order Items - Before Persisting/Creating Order Items
    */
    @Before(event = CdsService.EVENT_CREATE,entity = OrderItems_.CDS_NAME)
    public void validateBookAndDecreaseStock(List<OrderItems> items)
    {
        if(dbSrv != null && !CollectionUtils.isEmpty(items))
        {
            //Scan through Items
            for (OrderItems item : items)
            {
                String bookID = item.getBookId();
                int qty = item.getAmount();

                if(StringUtils.hasText(bookID))
                {
                    //Check Qty > 0 
                    if(qty > 0)
                    {
                        //Check if Book ID is valid

                        //Prepare Book select CQN
                        CqnSelect bookSelStr = Select.from(Books_.class).columns(b->b.stock())
                        .where(b->b.ID().eq(bookID));

                        Optional<Books> bookO = dbSrv.run(bookSelStr).first(Books.class);
                        if(bookO.isPresent())
                        {
                            Books book = bookO.get();
                            //check if Stock is More than Ordered Qty
                            if(book.getStock() >= qty)
                            {
                                book.setStock(book.getStock() - qty);
                                //Update the Quantity for Book for Next iteration Consideration
                                //Please note Books and Authors update Only Possible on Cappire/Developed in Products-Service APP 
                                //based Inherited Admin Service - Thus the Books class used in Update Query has to be from sap.capire namespace
                                CqnUpdate bookUpdateStr = Update.entity(cds.gen.sap.capire.bookstore.Books_.class)
                                                            .data(book).where(b->b.ID().eq(bookID));

                                //Trigger Updates                            
                                dbSrv.run(bookUpdateStr);                         


                            }
                            else
                            {
                                throw new ServiceException(ErrorStatuses.BAD_REQUEST, "Not enough books on stock");
                            }

                        }
                        else
                        {
                            throw new ServiceException(ErrorStatuses.NOT_FOUND, 
                             "Book with id : {1} not found!", new Object[]{bookID});
                        }
                    }
                    else
                    {
                        throw new ServiceException(ErrorStatuses.NOT_ACCEPTABLE, 
                        "Book with id : {1} in order #  :{2} and line Item #: {3} has '0' Quantity", 
                        new Object[]{bookID,item.getParentId(),item.getId()});
                    }

                   
                }
                else
                {
                    throw new ServiceException(ErrorStatuses.NOT_FOUND, 
                    "Book with id : {1} not found!", new Object[]{bookID});
                }
                
            }
        }
    }

    
    /*
     --- Validate Books and decrease Stock for Orders - Before Persisting/Creating Orders
    */
    @Before(event = CdsService.EVENT_CREATE,entity = Orders_.CDS_NAME)
    public void validateBookAndDecreaseStockViaOrders(List<Orders> orders)
    {
        if(!CollectionUtils.isEmpty(orders))
        {
            for (Orders order : orders) 
            {
                if(!CollectionUtils.isEmpty(order.getItems()))
                {
                    //Process for Each Order
                    validateBookAndDecreaseStock(order.getItems());
                }
            }
        }
    }


    /*
    --- Calculate Nett Amount for Order Items - After Reaading/Creating Order Item(s)
    */

    @After(event = {CdsService.EVENT_CREATE,CdsService.EVENT_READ}, entity = OrderItems_.CDS_NAME)
    public void calculateNettAmount(List<OrderItems> items)
    {
        if(!CollectionUtils.isEmpty(items))
        {
            for (OrderItems item : items)
            {
                String bookID = item.getBookId();
                if(StringUtils.hasText(bookID))
                {
                    CqnSelect bookSelStr = Select.from(Books_.class).columns(b->b.price())
                    .where(b->b.ID().eq(bookID));

                    Optional<Books> bookO = dbSrv.run(bookSelStr).first(Books.class);
                    if(bookO.isPresent())
                    {
                        //Not persisted since it is a @readOnly Attribute
                        item.setNetAmount(bookO.get().getPrice().multiply(new BigDecimal(item.getAmount())));
                    }
                }
                else
                {
                    throw new ServiceException(ErrorStatuses.NOT_FOUND, 
                    "Book with id : {1} not found!", new Object[]{bookID});
                }
                
            }
        }
    }


    @After(event = {CdsService.EVENT_CREATE,CdsService.EVENT_READ}, entity = Orders_.CDS_NAME)
   public void calculateOrderTotals(List<Orders> orders)
   {
     if(!CollectionUtils.isEmpty(orders))
     {
        List<OrderItems> itemsAll = null;

         for (Orders order : orders) 
         {

             // 1. Process Orders Items from Payload
            if(!CollectionUtils.isEmpty(order.getItems()))  
            {
                calculateNettAmount(order.getItems());
            }  

            /* 
             --- 2. Get all Order Items from DB - Need to calculate Nett. Amount for them too since it is a 
             calculated field and rightfully so as the currency rates might change
            */

            //Prepare Select
            CqnSelect CQN_selOrdItems = 
            Select.from(OrderItems_.class).where(q->q.parent().ID().eq(order.getId()));
            if(CQN_selOrdItems != null)
            {
                itemsAll =  dbSrv.run(CQN_selOrdItems).listOf(OrderItems.class);

                if(!CollectionUtils.isEmpty(itemsAll))
                {
                    calculateNettAmount(itemsAll);
                }
            }

        
               BigDecimal ordTotals = itemsAll.stream()
                     .map(OrderItems::getNetAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

                order.setTotal(ordTotals);

         }
     }
   }




}
