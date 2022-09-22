using { sap.capire.bookstore as db } from '../db/schema';

//Define Book Service
service BooksService
{
    //Books will be directly maintained via AdminService from Products exposed not via Books Service
    //Books Service will only act as a read repository on Books
    @readonly entity Books as projection on db.Books { *, category as genre} 
    excluding
    {
        category, createdAt,createdBy, modifiedAt,modifiedBy
    };

    @readonly entity Authors as projection on db.Authors;


}

//Define Orders Service
service OrdersService
{
    @(restrict:
     [
        { grant: '*', to: 'Administrators' },
        { grant: '*', where: 'createdBy = $user' }
    ])
    entity Orders as projection on db.Orders;

    @(restrict:
     [
        { grant: '*', to: 'Administrators' },
        { grant: '*', where: 'parent.createdBy = $user' }
    ])
    entity OrderItems as projection on db.OrderItems;
}

//Reuse Admin Service for Maintaining Books and Authors
using{AdminService} from '@sap/capire-products';
extend service  AdminService with
 {
     //Entity Products and Categories are already present in Admin Service
     entity Authors as projection on db.Authors;
 }

 annotate AdminService @(requires: 'Administrators');

